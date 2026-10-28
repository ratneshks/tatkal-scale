package com.tatkal.stampede;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.config.TatkalProperties;
import com.tatkal.hold.HoldAttempt;
import com.tatkal.hold.HoldCommand;
import com.tatkal.hold.HoldOrchestrator;
import com.tatkal.hold.HoldOutcome;
import com.tatkal.hold.StrategyType;
import com.tatkal.show.ShowAdminService;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fires N threads at the booking engine at the same instant and then checks, in the
 * database, whether any seat was sold twice.
 *
 * Three latches:
 *   ready : each worker counts down once it is parked at the gate
 *   start : the "starting pistol". Main thread opens it once ALL workers are parked,
 *           so every worker calls hold() at (as close as the OS allows to) the same moment
 *   done  : main waits for all workers to finish
 *
 * Calls the service layer directly (not HTTP) so we measure database contention,
 * not Tomcat's thread pool (200 by default).
 */
@Service
public class StampedeService {

    private static final Logger log = LoggerFactory.getLogger(StampedeService.class);

    private final HoldOrchestrator orchestrator;
    private final ShowAdminService admin;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final MeterRegistry meters;
    private final TatkalProperties props;

    /** One stampede at a time: two concurrent runs on the same arena would corrupt each other's numbers. */
    private final ReentrantLock runLock = new ReentrantLock();

    public StampedeService(HoldOrchestrator orchestrator, ShowAdminService admin, JdbcTemplate jdbc,
                           DataSource dataSource, MeterRegistry meters, TatkalProperties props) {
        this.orchestrator = orchestrator;
        this.admin = admin;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.meters = meters;
        this.props = props;
    }

    public StampedeReport run(StampedeRequest req) {
        if (!runLock.tryLock()) {
            throw new TatkalException(ErrorCode.STAMPEDE_ALREADY_RUNNING, "A stampede is already running");
        }
        try {
            return runLocked(req);
        } finally {
            runLock.unlock();
        }
    }

    public List<StampedeReport> compare(StampedeRequest req) {
        if (!runLock.tryLock()) {
            throw new TatkalException(ErrorCode.STAMPEDE_ALREADY_RUNNING, "A stampede is already running");
        }
        try {
            List<StampedeReport> reports = new ArrayList<>();
            for (StrategyType s : req.strategiesOrDefault()) {
                reports.add(runLocked(req.withStrategy(s)));
            }
            return reports;
        } finally {
            runLock.unlock();
        }
    }

    public List<Map<String, Object>> history(int limit) {
        return jdbc.queryForList("select * from stampede_run order by id desc limit ?", Math.min(limit, 500));
    }

    private StampedeReport runLocked(StampedeRequest req) {
        StrategyType strategy = req.strategyOrDefault();
        StampedeMode mode = req.modeOrDefault();
        int threads = req.threadsOrDefault();
        int seats = req.seatsOrDefault();
        int think = req.thinkTimeOrDefault();
        boolean guard = req.dbGuardOrDefault();
        boolean virtual = req.virtualThreadsOrDefault();
        validate(threads, seats, think);

        long showId = props.stampede().showId();

        // 1. Fresh arena: exactly `seats` AVAILABLE seats, no bookings.
        admin.resetShow(showId, seats);
        admin.setDbGuard(guard);
        Long targetSeat = mode == StampedeMode.SAME_SEAT
                ? jdbc.queryForObject("select min(id) from seat where show_id = ?", Long.class, showId)
                : null;

        try {
            // 2. Fire.
            HoldAttempt[] attempts = new HoldAttempt[threads];
            long[] latencyNanos = new long[threads];   // one slot per thread: no shared counter to contend on
            double acquireBefore = acquireMillisTotal();
            long acquireCountBefore = acquireCount();

            long wallNanos = fire(threads, virtual, i -> {
                long t0 = System.nanoTime();
                attempts[i] = orchestrator.attempt(strategy,
                        new HoldCommand(showId, targetSeat, "stampede-user-" + i, think));
                latencyNanos[i] = System.nanoTime() - t0;
            });

            long acquireCount = acquireCount() - acquireCountBefore;
            double avgAcquire = acquireCount == 0 ? 0 : (acquireMillisTotal() - acquireBefore) / acquireCount;

            // 3. Judge using the database, not our own counters.
            StampedeReport report = buildReport(strategy, mode, threads, seats, think, guard, virtual,
                    attempts, latencyNanos, wallNanos, avgAcquire, showId);
            save(report);
            log.info("Stampede {} {} threads={} seats={} -> {}", strategy, mode, threads, seats, report.verdict());
            return report;
        } finally {
            // 4. Always put the safety net back. Duplicate rows from an oversell would block
            //    re-creating the unique index, so wipe the arena first in that case only
            //    (otherwise the seat map is left as the stampede left it, for inspection).
            if (!guard) {
                if (hasDuplicateActiveBookings(showId)) {
                    admin.resetShow(showId, seats);
                }
                admin.setDbGuard(true);
            }
        }
    }

    private interface Worker {
        void run(int index);
    }

    /** @return wall-clock nanos from opening the gate until the last worker finished */
    private long fire(int threads, boolean virtual, Worker worker) {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = virtual
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();               // everyone waits here for the pistol
                        worker.run(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            awaitQuietly(ready, 30);                 // all workers parked at the gate
            long t0 = System.nanoTime();
            start.countDown();                       // BANG
            if (!awaitQuietly(done, 300)) {
                log.warn("Stampede did not finish within 300s");
            }
            return System.nanoTime() - t0;
        }
        // try-with-resources: ExecutorService.close() waits for tasks and shuts the pool down
    }

    private StampedeReport buildReport(StrategyType strategy, StampedeMode mode, int threads, int seats, int think,
                                       boolean guard, boolean virtual, HoldAttempt[] attempts, long[] latencyNanos,
                                       long wallNanos, double avgAcquire, long showId) {
        Map<HoldOutcome, Integer> outcomes = new EnumMap<>(HoldOutcome.class);
        for (HoldOutcome o : HoldOutcome.values()) {
            outcomes.put(o, 0);
        }
        long retries = 0;
        Set<String> errors = new LinkedHashSet<>();
        for (HoldAttempt a : attempts) {
            if (a == null) {
                outcomes.merge(HoldOutcome.ERROR, 1, Integer::sum);
                errors.add("worker did not finish");
                continue;
            }
            outcomes.merge(a.outcome(), 1, Integer::sum);
            retries += a.retries();
            if (a.outcome() == HoldOutcome.ERROR && errors.size() < 5) {
                errors.add(a.message());
            }
        }

        Map<String, Object> inv = jdbc.queryForMap("""
                select count(*) filter (where n > 1)              as oversold_seats,
                       coalesce(sum(n - 1) filter (where n > 1), 0) as extra_bookings,
                       coalesce(sum(n), 0)                         as active_bookings
                from (select b.seat_id, count(*) as n
                      from booking b join seat s on s.id = b.seat_id
                      where s.show_id = ? and b.status in ('PENDING_PAYMENT', 'CONFIRMED')
                      group by b.seat_id) per_seat
                """, showId);
        int oversold = ((Number) inv.get("oversold_seats")).intValue();
        int extra = ((Number) inv.get("extra_bookings")).intValue();
        int active = ((Number) inv.get("active_bookings")).intValue();
        int successes = outcomes.get(HoldOutcome.SUCCESS);
        boolean matches = successes == active;
        boolean holds = oversold == 0 && matches && successes <= seats;

        long[] sorted = latencyNanos.clone();
        Arrays.sort(sorted);
        var latency = new StampedeReport.Latency(pct(sorted, 50), pct(sorted, 95), pct(sorted, 99),
                sorted.length == 0 ? 0 : round(sorted[sorted.length - 1] / 1e6));

        double wallMs = wallNanos / 1e6;
        String verdict = holds
                ? "PASS: zero oversell (" + successes + "/" + seats + " seats sold, " + (threads - successes) + " buyers turned away)"
                : "FAIL: OVERSOLD " + oversold + " seat(s), " + extra + " ticket(s) sold that do not exist";

        return new StampedeReport(verdict, strategy, mode, threads, seats, think, guard, virtual, poolSize(),
                outcomes, retries, new StampedeReport.Invariant(active, oversold, extra, matches, holds), latency,
                round(wallMs), round(threads / (wallMs / 1000.0)), round(avgAcquire), List.copyOf(errors));
    }

    private void save(StampedeReport r) {
        var o = r.outcomes();
        jdbc.update("""
                insert into stampede_run (strategy, mode, threads, seats, db_guard, virtual_threads, think_time_ms,
                    successes, sold_out, retries_exhausted, lock_timeouts, db_guard_rejections, errors, total_retries,
                    active_bookings, oversold_seats, extra_bookings, p50_ms, p95_ms, p99_ms, max_ms, wall_ms, created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                r.strategy().name(), r.mode().name(), r.threads(), r.seats(), r.dbGuard(), r.virtualThreads(),
                r.thinkTimeMs(), o.get(HoldOutcome.SUCCESS), o.get(HoldOutcome.SOLD_OUT),
                o.get(HoldOutcome.RETRIES_EXHAUSTED), o.get(HoldOutcome.LOCK_TIMEOUT),
                o.get(HoldOutcome.DB_GUARD_REJECTED), o.get(HoldOutcome.ERROR), r.totalRetries(),
                r.invariant().activeBookings(), r.invariant().oversoldSeats(), r.invariant().extraBookings(),
                r.latency().p50Ms(), r.latency().p95Ms(), r.latency().p99Ms(), r.latency().maxMs(), r.wallMs(),
                java.sql.Timestamp.from(Instant.now()));
    }

    private boolean hasDuplicateActiveBookings(long showId) {
        Boolean dup = jdbc.queryForObject("""
                select exists (select 1 from booking b join seat s on s.id = b.seat_id
                               where s.show_id = ? and b.status in ('PENDING_PAYMENT', 'CONFIRMED')
                               group by b.seat_id having count(*) > 1)
                """, Boolean.class, showId);
        return Boolean.TRUE.equals(dup);
    }

    private void validate(int threads, int seats, int think) {
        if (threads < 1 || threads > props.stampede().maxThreads()) {
            throw new IllegalArgumentException("threads must be between 1 and " + props.stampede().maxThreads());
        }
        if (seats < 1 || seats > 1000) {
            throw new IllegalArgumentException("seats must be between 1 and 1000");
        }
        if (think < 0 || think > 1000) {
            throw new IllegalArgumentException("thinkTimeMs must be between 0 and 1000");
        }
    }

    private int poolSize() {
        return dataSource instanceof HikariDataSource h ? h.getMaximumPoolSize() : -1;
    }

    private Timer acquireTimer() {
        return meters.find("hikaricp.connections.acquire").timer();
    }

    private double acquireMillisTotal() {
        Timer t = acquireTimer();
        return t == null ? 0 : t.totalTime(TimeUnit.MILLISECONDS);
    }

    private long acquireCount() {
        Timer t = acquireTimer();
        return t == null ? 0 : t.count();
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;   // nearest-rank percentile
        return round(sorted[Math.max(0, idx)] / 1e6);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static boolean awaitQuietly(CountDownLatch latch, int seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
