package com.tatkal.hold;

import com.tatkal.booking.Booking;
import com.tatkal.booking.BookingRepository;
import com.tatkal.config.TatkalProperties;
import com.tatkal.seat.Seat;
import com.tatkal.seat.SeatRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared "write" half of every JPA-based strategy. Strategies differ only in HOW they
 * read/lock the seat; once they have it, the write is identical.
 *
 * MANDATORY propagation: this must run inside the caller's transaction, never its own.
 */
@Component
class HoldWriter {

    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final TatkalProperties props;

    HoldWriter(SeatRepository seats, BookingRepository bookings, TatkalProperties props) {
        this.seats = seats;
        this.bookings = bookings;
        this.props = props;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public HoldResult apply(Seat seat, HoldCommand cmd, Instant now, StrategyType strategy) {
        // If we are taking over a lazily-expired hold, remember its token so its booking can be retired.
        UUID staleToken = seat.isExpiredHoldAt(now) ? seat.getHoldToken() : null;

        UUID token = seat.hold(cmd.userId(), now, props.hold().ttl());

        // Flush the seat UPDATE *now*, before inserting the booking. Hibernate would otherwise
        // run INSERTs before UPDATEs at commit, so an optimistic loser would first collide on the
        // booking unique index instead of failing cleanly on the version check.
        // Flushing through the repository also gets Spring's exception translation
        // (ObjectOptimisticLockingFailureException instead of a raw JPA exception).
        seats.flush();

        if (staleToken != null) {
            bookings.expireByHoldToken(staleToken, now);
        }
        bookings.saveAndFlush(new Booking(seat.getId(), cmd.userId(), token, strategy, now));

        return new HoldResult(token, seat.getId(), seat.getSeatNo(), cmd.userId(),
                seat.getHoldExpiresAt(), strategy, 1);
    }
}
