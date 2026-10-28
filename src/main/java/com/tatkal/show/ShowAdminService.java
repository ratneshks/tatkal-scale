package com.tatkal.show;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-only operations used to set up experiments. Not part of the booking flow.
 */
@Service
public class ShowAdminService {

    static final String GUARD_INDEX = "ux_booking_one_active_per_seat";

    private final JdbcTemplate jdbc;

    public ShowAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Wipes a show's bookings and recreates exactly {@code seatCount} AVAILABLE seats (version 0). */
    @Transactional
    public void resetShow(long showId, int seatCount) {
        if (seatCount < 1 || seatCount > 10_000) {
            throw new IllegalArgumentException("seatCount must be between 1 and 10000");
        }
        Integer exists = jdbc.queryForObject("select count(*) from show where id = ?", Integer.class, showId);
        if (exists == null || exists == 0) {
            throw new TatkalException(ErrorCode.NOT_FOUND, "Show " + showId + " not found");
        }
        jdbc.update("delete from booking where seat_id in (select id from seat where show_id = ?)", showId);
        jdbc.update("delete from seat where show_id = ?", showId);
        jdbc.update("insert into seat (show_id, seat_no, status) "
                + "select ?, 'A-' || g, 'AVAILABLE' from generate_series(1, ?) as g", showId, seatCount);
        jdbc.update("update show set total_seats = ? where id = ?", seatCount, showId);
    }

    public boolean isDbGuardPresent() {
        Integer n = jdbc.queryForObject("select count(*) from pg_indexes where indexname = ?", Integer.class, GUARD_INDEX);
        return n != null && n > 0;
    }

    /**
     * Demo switch for the NAIVE strategy only: drop the partial unique index so the oversell
     * becomes visible as duplicate bookings. Always restored right after the run.
     */
    public void setDbGuard(boolean enabled) {
        if (enabled && !isDbGuardPresent()) {
            jdbc.execute("CREATE UNIQUE INDEX " + GUARD_INDEX + " ON booking (seat_id) "
                    + "WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED')");
        } else if (!enabled && isDbGuardPresent()) {
            jdbc.execute("DROP INDEX " + GUARD_INDEX);
        }
    }
}
