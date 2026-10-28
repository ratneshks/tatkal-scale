package com.tatkal.sweeper;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Releases every expired hold in ONE set-based SQL statement (no load-then-loop in Java):
 *
 *  1. expired  : pick HELD seats past their TTL, locking them FOR UPDATE SKIP LOCKED.
 *                SKIP LOCKED means a seat that a confirm/release is working on right now is
 *                left alone; if that confirm fails, the next sweep picks the seat up.
 *  2. released : flip them to AVAILABLE and bump version, so any optimistic writer holding
 *                a stale copy of the seat fails its version check instead of overwriting us.
 *  3. bookings : mark the matching PENDING_PAYMENT bookings EXPIRED.
 *
 * Safe to run on several app instances at once: rows locked by one sweeper are skipped by the
 * other, and a re-run finds nothing left to do (idempotent).
 */
@Service
public class ExpiredHoldReleaser {

    private static final String SQL = """
            WITH expired AS (
                SELECT id, hold_token
                FROM seat
                WHERE status = 'HELD' AND hold_expires_at <= :now
                FOR UPDATE SKIP LOCKED
            ),
            released AS (
                UPDATE seat s
                SET status = 'AVAILABLE', held_by = NULL, hold_token = NULL,
                    hold_expires_at = NULL, version = s.version + 1
                FROM expired e
                WHERE s.id = e.id
                RETURNING e.hold_token
            ),
            expired_bookings AS (
                UPDATE booking b
                SET status = 'EXPIRED', updated_at = :now
                FROM released r
                WHERE b.hold_token = r.hold_token AND b.status = 'PENDING_PAYMENT'
                RETURNING b.id
            )
            SELECT count(*) FROM released
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ExpiredHoldReleaser(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return number of seats returned to the pool */
    @Transactional
    public int releaseExpired(Instant now) {
        Integer n = jdbc.queryForObject(SQL, new MapSqlParameterSource("now", Timestamp.from(now)), Integer.class);
        return n == null ? 0 : n;
    }
}
