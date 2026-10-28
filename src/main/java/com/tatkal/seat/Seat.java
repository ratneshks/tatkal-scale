package com.tatkal.seat;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A seat and its state machine. All transitions live here (rich domain model), so no
 * service can skip a rule. Illegal moves throw instead of silently corrupting state.
 */
@Entity
@Table(name = "seat")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Plain FK column instead of @ManyToOne: the hot path never needs the Show, and
    // avoiding the association avoids lazy proxies and extra joins under load.
    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "seat_no", nullable = false)
    private String seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    @Column(name = "held_by")
    private String heldBy;

    @Column(name = "hold_token")
    private UUID holdToken;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    /**
     * Optimistic lock. Hibernate turns every update into
     * UPDATE seat SET ..., version = v+1 WHERE id = ? AND version = v
     * and throws if 0 rows changed (someone else updated first).
     */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Seat() {
        // for JPA
    }

    public Seat(Long showId, String seatNo) {
        this.showId = showId;
        this.seatNo = seatNo;
        this.status = SeatStatus.AVAILABLE;
    }

    /** Lazy expiry: a HELD seat whose TTL has passed counts as free, even before the sweeper runs. */
    public boolean isHoldableAt(Instant now) {
        return status == SeatStatus.AVAILABLE || isExpiredHoldAt(now);
    }

    public boolean isExpiredHoldAt(Instant now) {
        return status == SeatStatus.HELD && holdExpiresAt != null && !holdExpiresAt.isAfter(now);
    }

    /**
     * Fail fast: strategies call this right after reading/locking the seat, BEFORE doing any
     * other work. Otherwise a loser would do its work while holding the row lock and make
     * everyone queued behind it wait for nothing.
     */
    public void requireHoldableAt(Instant now) {
        if (!isHoldableAt(now)) {
            throw new TatkalException(ErrorCode.SEAT_UNAVAILABLE, "Seat " + seatNo + " is " + status);
        }
    }

    /** AVAILABLE (or expired HELD) -> HELD. Returns the new hold token. */
    public UUID hold(String userId, Instant now, Duration ttl) {
        requireHoldableAt(now);
        this.status = SeatStatus.HELD;
        this.heldBy = Objects.requireNonNull(userId);
        this.holdToken = UUID.randomUUID();
        this.holdExpiresAt = now.plus(ttl);
        return holdToken;
    }

    /** HELD -> BOOKED, only for the current holder and only before the TTL. */
    public void confirm(UUID token, Instant now) {
        requireActiveHold(token);
        if (!holdExpiresAt.isAfter(now)) {
            throw new TatkalException(ErrorCode.HOLD_EXPIRED, "Hold expired at " + holdExpiresAt);
        }
        this.status = SeatStatus.BOOKED;
        this.holdExpiresAt = null;
    }

    /** HELD -> AVAILABLE (user cancelled). */
    public void release(UUID token) {
        requireActiveHold(token);
        clearHold();
    }

    private void requireActiveHold(UUID token) {
        if (status != SeatStatus.HELD || !Objects.equals(holdToken, token)) {
            // The seat moved on (expired and re-held, released, or booked by this token already)
            throw new TatkalException(ErrorCode.HOLD_EXPIRED, "Hold " + token + " is no longer active on seat " + seatNo);
        }
    }

    private void clearHold() {
        this.status = SeatStatus.AVAILABLE;
        this.heldBy = null;
        this.holdToken = null;
        this.holdExpiresAt = null;
    }

    public Long getId() {
        return id;
    }

    public Long getShowId() {
        return showId;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public String getHeldBy() {
        return heldBy;
    }

    public UUID getHoldToken() {
        return holdToken;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Long getVersion() {
        return version;
    }
}
