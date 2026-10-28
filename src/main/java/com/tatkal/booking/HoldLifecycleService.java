package com.tatkal.booking;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.seat.Seat;
import com.tatkal.seat.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * What happens to a hold after it is placed: confirm (payment done) or release (user cancels).
 *
 * LOCK ORDER: always seat row first, then booking row. The sweeper uses the same order
 * (seat, then booking), so these paths can never deadlock each other.
 *
 * Both operations are idempotent per hold token: repeating a confirm returns the same
 * confirmed booking instead of an error (double-click on "Pay", client retry after timeout).
 */
@Service
public class HoldLifecycleService {

    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final Clock clock;

    public HoldLifecycleService(SeatRepository seats, BookingRepository bookings, Clock clock) {
        this.seats = seats;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BookingView get(UUID token) {
        return BookingView.of(bookings.findByHoldToken(token).orElseThrow(() -> notFound(token)));
    }

    @Transactional
    public BookingView confirm(UUID token) {
        Instant now = clock.instant();
        Locked locked = lock(token);
        Booking booking = locked.booking();

        switch (booking.getStatus()) {
            case CONFIRMED -> {
                return BookingView.of(booking); // idempotent repeat
            }
            case EXPIRED -> throw new TatkalException(ErrorCode.HOLD_EXPIRED, "Hold expired before payment");
            case CANCELLED -> throw new TatkalException(ErrorCode.HOLD_NOT_ACTIVE, "Hold was cancelled");
            case PENDING_PAYMENT -> {
                // Seat.confirm checks the token still owns the seat AND the TTL has not passed.
                locked.seat().confirm(token, now);
                booking.confirm(now);
                return BookingView.of(booking);
            }
        }
        throw new IllegalStateException("Unhandled status " + booking.getStatus());
    }

    @Transactional
    public BookingView release(UUID token) {
        Instant now = clock.instant();
        Locked locked = lock(token);
        Booking booking = locked.booking();

        switch (booking.getStatus()) {
            case CANCELLED, EXPIRED -> {
                return BookingView.of(booking); // already released: idempotent
            }
            case CONFIRMED -> throw new TatkalException(ErrorCode.HOLD_NOT_ACTIVE, "Already booked; cancellation is not supported");
            case PENDING_PAYMENT -> {
                Seat seat = locked.seat();
                if (seat.getStatus() == com.tatkal.seat.SeatStatus.HELD && token.equals(seat.getHoldToken())) {
                    seat.release(token);
                }
                booking.cancel(now);
                return BookingView.of(booking);
            }
        }
        throw new IllegalStateException("Unhandled status " + booking.getStatus());
    }

    private record Locked(Seat seat, Booking booking) {
    }

    private Locked lock(UUID token) {
        // seat_id is immutable, so reading it unlocked is safe and tells us which row to lock first.
        Long seatId = bookings.findSeatIdByHoldToken(token).orElseThrow(() -> notFound(token));
        Seat seat = seats.lockById(seatId).orElseThrow(() -> notFound(token));
        Booking booking = bookings.lockByHoldToken(token).orElseThrow(() -> notFound(token));
        return new Locked(seat, booking);
    }

    private static TatkalException notFound(UUID token) {
        return new TatkalException(ErrorCode.HOLD_NOT_FOUND, "No hold with token " + token);
    }
}
