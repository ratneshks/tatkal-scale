package com.tatkal.seat;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    // "Holdable" = AVAILABLE, or HELD with an expired TTL (lazy expiry; see Seat#isHoldableAt).
    String HOLDABLE_JPQL = "(s.status = com.tatkal.seat.SeatStatus.AVAILABLE "
            + "or (s.status = com.tatkal.seat.SeatStatus.HELD and s.holdExpiresAt <= :now))";
    String HOLDABLE_SQL = "(status = 'AVAILABLE' or (status = 'HELD' and hold_expires_at <= :now))";

    List<Seat> findByShowIdOrderByIdAsc(Long showId);

    Optional<Seat> findByIdAndShowId(Long id, Long showId);

    // ---------- plain reads (NAIVE / OPTIMISTIC) ----------

    @Query("select s from Seat s where s.showId = :showId and " + HOLDABLE_JPQL + " order by s.id")
    List<Seat> findHoldable(@Param("showId") Long showId, @Param("now") Instant now, Pageable page);

    @Query("select count(s) from Seat s where s.showId = :showId and " + HOLDABLE_JPQL)
    long countHoldable(@Param("showId") Long showId, @Param("now") Instant now);

    // ---------- PESSIMISTIC: SELECT ... FOR UPDATE ----------

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id and s.showId = :showId")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id, @Param("showId") Long showId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.showId = :showId and " + HOLDABLE_JPQL + " order by s.id")
    List<Seat> findHoldableForUpdate(@Param("showId") Long showId, @Param("now") Instant now, Pageable page);

    // ---------- SKIP LOCKED: take a seat nobody else is touching ----------

    @Query(value = "select * from seat where show_id = :showId and " + HOLDABLE_SQL
            + " order by id limit 1 for update skip locked", nativeQuery = true)
    Optional<Seat> findHoldableSkipLocked(@Param("showId") Long showId, @Param("now") Instant now);

    @Query(value = "select * from seat where id = :id and show_id = :showId for update skip locked", nativeQuery = true)
    Optional<Seat> findByIdSkipLocked(@Param("id") Long id, @Param("showId") Long showId);
}
