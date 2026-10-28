package com.tatkal.hold;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.config.TatkalProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks the strategy and owns the RETRY LOOP.
 *
 * Deliberately NOT @Transactional. Each call to strategy.tryOnce() goes through the Spring proxy
 * of a different bean, so every attempt gets a brand-new transaction and persistence context.
 * Retrying inside one transaction would reuse a rollback-only transaction and stale entities.
 */
@Service
public class HoldOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(HoldOrchestrator.class);

    private final Map<StrategyType, SeatHoldStrategy> strategies = new EnumMap<>(StrategyType.class);
    private final TatkalProperties props;
    private final MeterRegistry meters;

    public HoldOrchestrator(List<SeatHoldStrategy> all, TatkalProperties props, MeterRegistry meters) {
        all.forEach(s -> strategies.put(s.type(), s));
        this.props = props;
        this.meters = meters;
    }

    /** For REST: success returns the hold, every failure becomes a TatkalException (HTTP problem). */
    public HoldResult hold(StrategyType type, HoldCommand cmd) {
        HoldAttempt a = attempt(type, cmd);
        if (a.outcome() == HoldOutcome.SUCCESS) {
            return a.result();
        }
        throw new TatkalException(a.errorCode(), a.message());
    }

    /** For the simulator: never throws for business outcomes, just reports what happened. */
    public HoldAttempt attempt(StrategyType type, HoldCommand cmd) {
        SeatHoldStrategy strategy = strategies.get(type);
        int max = Math.max(1, props.hold().maxAttempts());
        HoldAttempt result = null;
        for (int attempt = 1; attempt <= max && result == null; attempt++) {
            try {
                result = HoldAttempt.success(strategy.tryOnce(cmd).withAttempts(attempt));
            } catch (RuntimeException e) {
                if (isRetryable(e)) {
                    if (attempt == max) {
                        result = HoldAttempt.failure(HoldOutcome.RETRIES_EXHAUSTED, attempt,
                                ErrorCode.CONCURRENT_MODIFICATION, "Lost " + attempt + " races in a row, try again");
                    } else {
                        backoff(attempt);
                    }
                } else {
                    result = classify(e, attempt);
                }
            }
        }
        meters.counter("tatkal.hold.outcome", "strategy", type.name(), "outcome", result.outcome().name()).increment();
        if (result.retries() > 0) {
            meters.counter("tatkal.hold.retries", "strategy", type.name()).increment(result.retries());
        }
        return result;
    }

    private static boolean isRetryable(Throwable e) {
        return e instanceof RetryableConflictException
                || e instanceof OptimisticLockingFailureException          // Spring-translated @Version conflict
                || hasCause(e, jakarta.persistence.OptimisticLockException.class);
    }

    private static HoldAttempt classify(RuntimeException e, int attempt) {
        if (e instanceof TatkalException te) {
            HoldOutcome outcome = switch (te.code()) {
                case SEAT_UNAVAILABLE, SOLD_OUT -> HoldOutcome.SOLD_OUT;
                default -> HoldOutcome.ERROR;
            };
            return HoldAttempt.failure(outcome, attempt, te.code(), te.getMessage());
        }
        String state = sqlState(e);
        if (e instanceof PessimisticLockingFailureException || "55P03".equals(state) || "40P01".equals(state)) {
            return HoldAttempt.failure(HoldOutcome.LOCK_TIMEOUT, attempt, ErrorCode.LOCK_TIMEOUT,
                    "Timed out waiting for the seat lock");
        }
        if (e instanceof DataIntegrityViolationException || "23505".equals(state)) {
            return HoldAttempt.failure(HoldOutcome.DB_GUARD_REJECTED, attempt, ErrorCode.DB_GUARD_REJECTED,
                    "Database refused a second active booking for this seat");
        }
        log.warn("Unexpected hold failure", e);
        return HoldAttempt.failure(HoldOutcome.ERROR, attempt, ErrorCode.INTERNAL_ERROR, e.getClass().getSimpleName());
    }

    /** Exponential backoff with full jitter, so retrying losers don't stampede again in lockstep. */
    private void backoff(int attempt) {
        long base = props.hold().backoffBase().toMillis();
        long cap = props.hold().backoffMax().toMillis();
        long ceiling = Math.min(cap, base * (1L << Math.min(attempt, 20)));
        long sleep = ThreadLocalRandom.current().nextLong(ceiling + 1);
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
