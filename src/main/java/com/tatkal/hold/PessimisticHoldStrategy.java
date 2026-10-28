package com.tatkal.hold;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.config.TatkalProperties;
import com.tatkal.seat.Seat;
import com.tatkal.seat.SeatRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Pessimistic locking: @Lock(PESSIMISTIC_WRITE) makes Hibernate emit SELECT ... FOR UPDATE
 * (PostgreSQL may get FOR NO KEY UPDATE, which still blocks other writers of the row).
 * Every other transaction that wants the same row WAITS until we commit or roll back, then
 * re-reads the committed row. No wasted work, but threads queue up behind a hot row.
 *
 * Postgres subtlety (ANY_SEAT mode): a waiter blocked on "first free seat ... LIMIT 1 FOR UPDATE"
 * re-checks the WHERE clause on the row after the winner commits. The row no longer matches,
 * so the query returns ZERO rows instead of moving on to the next free seat. We detect that
 * and retry, which is why this strategy can also report retries.
 */
@Service
public class PessimisticHoldStrategy implements SeatHoldStrategy {

    private final SeatRepository seats;
    private final HoldWriter writer;
    private final EntityManager em;
    private final Clock clock;
    private final TatkalProperties props;

    public PessimisticHoldStrategy(SeatRepository seats, HoldWriter writer, EntityManager em,
                                   Clock clock, TatkalProperties props) {
        this.seats = seats;
        this.writer = writer;
        this.em = em;
        this.clock = clock;
        this.props = props;
    }

    @Override
    public StrategyType type() {
        return StrategyType.PESSIMISTIC;
    }

    @Override
    @Transactional
    public HoldResult tryOnce(HoldCommand cmd) {
        // Bound the wait so a stuck holder cannot freeze every other buyer.
        // set_config(..., true) = SET LOCAL: applies to this transaction only.
        em.createNativeQuery("select set_config('lock_timeout', :v, true)")
                .setParameter("v", props.hold().lockTimeout().toMillis() + "ms")
                .getSingleResult();

        Instant now = clock.instant();
        Seat seat;
        if (cmd.seatId() != null) {
            seat = seats.findByIdForUpdate(cmd.seatId(), cmd.showId())
                    .orElseThrow(() -> new TatkalException(ErrorCode.NOT_FOUND, "Seat " + cmd.seatId() + " not found"));
        } else {
            seat = seats.findHoldableForUpdate(cmd.showId(), now, PageRequest.of(0, 1)).stream().findFirst()
                    .orElseGet(() -> {
                        if (seats.countHoldable(cmd.showId(), now) > 0) {
                            throw new RetryableConflictException("Locked row was taken while we waited");
                        }
                        throw new TatkalException(ErrorCode.SOLD_OUT, "No seats left");
                    });
        }

        // Fail fast BEFORE doing work under the lock. Without this, every loser in the queue
        // would also "think" while holding the lock (measured: p50 3.7s -> see README).
        seat.requireHoldableAt(now);

        cmd.think(); // happens while we hold the row lock: everyone else waits for it

        return writer.apply(seat, cmd, now, type());
    }
}
