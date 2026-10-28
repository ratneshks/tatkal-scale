package com.tatkal.booking;

import com.tatkal.hold.StrategyType;

import java.time.Instant;
import java.util.UUID;

public record BookingView(long bookingId, long seatId, String userId, UUID holdToken,
                          BookingStatus status, StrategyType strategy, Instant createdAt, Instant updatedAt) {

    static BookingView of(Booking b) {
        return new BookingView(b.getId(), b.getSeatId(), b.getUserId(), b.getHoldToken(),
                b.getStatus(), b.getStrategy(), b.getCreatedAt(), b.getUpdatedAt());
    }
}
