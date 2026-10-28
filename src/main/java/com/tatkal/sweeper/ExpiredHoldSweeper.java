package com.tatkal.sweeper;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Background job: every 30s, return unpaid expired holds to the pool.
 *
 * fixedDelay (not fixedRate): the next run starts 30s after the previous one FINISHES, so two
 * sweeps never overlap even if one is slow.
 *
 * The transactional work lives in a separate bean (ExpiredHoldReleaser) so the call goes through
 * Spring's proxy and the @Transactional annotation actually applies.
 *
 * Correctness never depends on this job: hold logic already treats an expired HELD seat as free
 * (lazy expiry). The sweeper just keeps the data tidy and the seat map honest.
 */
@Component
public class ExpiredHoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredHoldSweeper.class);

    private final ExpiredHoldReleaser releaser;
    private final Clock clock;
    private final MeterRegistry meters;

    public ExpiredHoldSweeper(ExpiredHoldReleaser releaser, Clock clock, MeterRegistry meters) {
        this.releaser = releaser;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${tatkal.sweeper.delay:30s}", initialDelayString = "${tatkal.sweeper.delay:30s}")
    public void sweep() {
        try {
            int released = releaser.releaseExpired(clock.instant());
            meters.counter("tatkal.sweeper.released").increment(released);
            if (released > 0) {
                log.info("Sweeper released {} expired hold(s)", released);
            }
        } catch (RuntimeException e) {
            // never let one bad run kill the schedule
            log.error("Sweeper run failed", e);
        }
    }
}
