package com.tatkal.hold;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.seat.Seat;
import com.tatkal.seat.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * SELECT ... FOR UPDATE SKIP LOCKED: instead of queuing behind a locked seat, skip it and
 * lock the next free one. 500 buyers fan out across the 5 seats at once instead of all
 * fighting over seat #1. This is how job queues and real ticketing systems hand out work.
 *
 * Trade-off: you get "a" seat, not a chosen one. For a specific seat it behaves like NOWAIT
 * (fail fast if someone else is mid-transaction on it).
 */
@Service
public class SkipLockedHoldStrategy implements SeatHoldStrategy {

    private final SeatRepository seats;
    private final HoldWriter writer;
    private final Clock clock;

    public SkipLockedHoldStrategy(SeatRepository seats, HoldWriter writer, Clock clock) {
        this.seats = seats;
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    public StrategyType type() {
        return StrategyType.SKIP_LOCKED;
    }

    @Override
    @Transactional
    public HoldResult tryOnce(HoldCommand cmd) {
        Instant now = clock.instant();
        Seat seat;
        if (cmd.seatId() != null) {
            seat = seats.findByIdSkipLocked(cmd.seatId(), cmd.showId()).orElseGet(() -> {
                Seat current = seats.findByIdAndShowId(cmd.seatId(), cmd.showId())
                        .orElseThrow(() -> new TatkalException(ErrorCode.NOT_FOUND, "Seat " + cmd.seatId() + " not found"));
                if (current.isHoldableAt(now)) {
                    // someone is mid-transaction on it; it might still come free if they fail
                    throw new RetryableConflictException("Seat is locked by another buyer");
                }
                throw new TatkalException(ErrorCode.SEAT_UNAVAILABLE, "Seat " + current.getSeatNo() + " is " + current.getStatus());
            });
        } else {
            seat = seats.findHoldableSkipLocked(cmd.showId(), now).orElseGet(() -> {
                if (seats.countHoldable(cmd.showId(), now) > 0) {
                    // every free seat is locked by an in-flight buyer right now
                    throw new RetryableConflictException("All free seats are locked right now");
                }
                throw new TatkalException(ErrorCode.SOLD_OUT, "No seats left");
            });
        }

        seat.requireHoldableAt(now); // fail fast before doing work under the lock

        cmd.think();

        return writer.apply(seat, cmd, now, type());
    }
}
