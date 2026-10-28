package com.tatkal.hold;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.seat.Seat;
import com.tatkal.seat.SeatRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Optimistic locking with JPA @Version.
 *
 * Read without any lock, change the entity, and let Hibernate issue
 *   UPDATE seat SET ..., version = v+1 WHERE id = ? AND version = v
 * If another transaction committed first, 0 rows match and Spring throws
 * ObjectOptimisticLockingFailureException. HoldOrchestrator then retries in a NEW transaction.
 *
 * Good when conflicts are rare (no one waits on locks). Wasteful on a hot seat:
 * every loser did the full read + think + write before finding out.
 */
@Service
public class OptimisticHoldStrategy implements SeatHoldStrategy {

    private final SeatRepository seats;
    private final HoldWriter writer;
    private final Clock clock;

    public OptimisticHoldStrategy(SeatRepository seats, HoldWriter writer, Clock clock) {
        this.seats = seats;
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    public StrategyType type() {
        return StrategyType.OPTIMISTIC;
    }

    @Override
    @Transactional
    public HoldResult tryOnce(HoldCommand cmd) {
        Instant now = clock.instant();
        Seat seat = cmd.seatId() != null
                ? seats.findByIdAndShowId(cmd.seatId(), cmd.showId())
                    .orElseThrow(() -> new TatkalException(ErrorCode.NOT_FOUND, "Seat " + cmd.seatId() + " not found"))
                : seats.findHoldable(cmd.showId(), now, PageRequest.of(0, 1)).stream().findFirst()
                    .orElseThrow(() -> new TatkalException(ErrorCode.SOLD_OUT, "No seats left"));

        // Already taken? Say so now (no retry needed) instead of after doing the work.
        seat.requireHoldableAt(now);

        cmd.think();

        // The version check inside writer.apply() catches the case where the seat was taken
        // between our read and our write (-> retry in a new transaction).
        return writer.apply(seat, cmd, now, type());
    }
}
