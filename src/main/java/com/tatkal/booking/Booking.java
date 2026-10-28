package com.tatkal.booking;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.hold.StrategyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per hold attempt that succeeded. The partial unique index
 * ux_booking_one_active_per_seat guarantees at most one PENDING_PAYMENT/CONFIRMED row per seat,
 * which is also how the Stampede Simulator detects overselling.
 */
@Entity
@Table(name = "booking")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "hold_token", nullable = false, unique = true)
    private UUID holdToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StrategyType strategy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Booking() {
        // for JPA
    }

    public Booking(Long seatId, String userId, UUID holdToken, StrategyType strategy, Instant now) {
        this.seatId = seatId;
        this.userId = userId;
        this.holdToken = holdToken;
        this.strategy = strategy;
        this.status = BookingStatus.PENDING_PAYMENT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void confirm(Instant now) {
        requirePending();
        this.status = BookingStatus.CONFIRMED;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        requirePending();
        this.status = BookingStatus.CANCELLED;
        this.updatedAt = now;
    }

    private void requirePending() {
        if (status != BookingStatus.PENDING_PAYMENT) {
            throw new TatkalException(ErrorCode.ILLEGAL_STATE_TRANSITION, "Booking is " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getSeatId() {
        return seatId;
    }

    public String getUserId() {
        return userId;
    }

    public UUID getHoldToken() {
        return holdToken;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public StrategyType getStrategy() {
        return strategy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
