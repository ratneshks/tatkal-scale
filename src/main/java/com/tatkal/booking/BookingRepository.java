package com.tatkal.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByHoldToken(UUID holdToken);

    /** seat_id never changes, so it is safe to read without a lock (used to pick the lock order). */
    @Query("select b.seatId from Booking b where b.holdToken = :token")
    Optional<Long> findSeatIdByHoldToken(@Param("token") UUID token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.holdToken = :token")
    Optional<Booking> lockByHoldToken(@Param("token") UUID token);

    /** Used when a lazily-expired hold is taken over by a new holder in the same transaction. */
    @Modifying(flushAutomatically = true)
    @Query("update Booking b set b.status = com.tatkal.booking.BookingStatus.EXPIRED, b.updatedAt = :now "
            + "where b.holdToken = :token and b.status = com.tatkal.booking.BookingStatus.PENDING_PAYMENT")
    int expireByHoldToken(@Param("token") UUID token, @Param("now") Instant now);
}
