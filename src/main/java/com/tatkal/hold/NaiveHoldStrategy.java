package com.tatkal.hold;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.config.TatkalProperties;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE BROKEN ONE, on purpose. Classic check-then-act:
 *   1. SELECT the seat, see AVAILABLE
 *   2. (think)
 *   3. UPDATE it to HELD with no version check and no status condition
 *
 * Under READ COMMITTED many threads see AVAILABLE in step 1 before anyone commits step 3,
 * so all of them "win". Plain JDBC is used because the JPA entity's @Version would
 * accidentally protect us.
 */
@Service
public class NaiveHoldStrategy implements SeatHoldStrategy {

    private static final String HOLDABLE = "(status = 'AVAILABLE' or (status = 'HELD' and hold_expires_at <= :now))";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final TatkalProperties props;

    public NaiveHoldStrategy(NamedParameterJdbcTemplate jdbc, Clock clock, TatkalProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.props = props;
    }

    @Override
    public StrategyType type() {
        return StrategyType.NAIVE;
    }

    @Override
    @Transactional
    public HoldResult tryOnce(HoldCommand cmd) {
        Instant now = clock.instant();
        var params = new MapSqlParameterSource()
                .addValue("showId", cmd.showId())
                .addValue("seatId", cmd.seatId())
                .addValue("now", Timestamp.from(now));

        // 1. CHECK (a plain read: no lock, no version)
        Map<String, Object> seat;
        if (cmd.seatId() != null) {
            try {
                seat = jdbc.queryForMap("select id, seat_no, status, hold_token, hold_expires_at from seat "
                        + "where id = :seatId and show_id = :showId", params);
            } catch (EmptyResultDataAccessException e) {
                throw new TatkalException(ErrorCode.NOT_FOUND, "Seat " + cmd.seatId() + " not found");
            }
            if (!isHoldable(seat, now)) {
                throw new TatkalException(ErrorCode.SEAT_UNAVAILABLE, "Seat " + seat.get("seat_no") + " is " + seat.get("status"));
            }
        } else {
            List<Map<String, Object>> rows = jdbc.queryForList("select id, seat_no, status, hold_token, hold_expires_at "
                    + "from seat where show_id = :showId and " + HOLDABLE + " order by id limit 1", params);
            if (rows.isEmpty()) {
                throw new TatkalException(ErrorCode.SOLD_OUT, "No seats left");
            }
            seat = rows.get(0);
        }

        cmd.think();

        // 2. ACT (blind write: trusts the stale read above)
        long seatId = ((Number) seat.get("id")).longValue();
        UUID token = UUID.randomUUID();
        Instant expiresAt = now.plus(props.hold().ttl());
        params.addValue("id", seatId)
                .addValue("userId", cmd.userId())
                .addValue("token", token)
                .addValue("expiresAt", Timestamp.from(expiresAt));

        jdbc.update("update seat set status = 'HELD', held_by = :userId, hold_token = :token, "
                + "hold_expires_at = :expiresAt where id = :id", params);

        Object staleToken = seat.get("hold_token");
        if (staleToken != null) {
            jdbc.update("update booking set status = 'EXPIRED', updated_at = :now "
                    + "where hold_token = :stale and status = 'PENDING_PAYMENT'", params.addValue("stale", staleToken));
        }
        jdbc.update("insert into booking (seat_id, user_id, hold_token, status, strategy, created_at, updated_at) "
                + "values (:id, :userId, :token, 'PENDING_PAYMENT', 'NAIVE', :now, :now)", params);

        return new HoldResult(token, seatId, (String) seat.get("seat_no"), cmd.userId(), expiresAt, type(), 1);
    }

    private static boolean isHoldable(Map<String, Object> seat, Instant now) {
        String status = (String) seat.get("status");
        if ("AVAILABLE".equals(status)) {
            return true;
        }
        Object exp = seat.get("hold_expires_at");
        return "HELD".equals(status) && exp instanceof Timestamp ts && !ts.toInstant().isAfter(now);
    }
}
