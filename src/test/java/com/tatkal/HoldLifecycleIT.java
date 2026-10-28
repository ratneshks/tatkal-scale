package com.tatkal;

import com.tatkal.booking.BookingRepository;
import com.tatkal.booking.BookingStatus;
import com.tatkal.booking.BookingView;
import com.tatkal.booking.HoldLifecycleService;
import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import com.tatkal.hold.HoldCommand;
import com.tatkal.hold.HoldOrchestrator;
import com.tatkal.hold.HoldResult;
import com.tatkal.hold.StrategyType;
import com.tatkal.seat.Seat;
import com.tatkal.seat.SeatRepository;
import com.tatkal.seat.SeatStatus;
import com.tatkal.show.ShowAdminService;
import com.tatkal.support.AbstractPostgresIT;
import com.tatkal.support.MutableClock;
import com.tatkal.sweeper.ExpiredHoldReleaser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HoldLifecycleIT extends AbstractPostgresIT {

    private static final long SHOW = 2;

    @Autowired HoldOrchestrator orchestrator;
    @Autowired HoldLifecycleService lifecycle;
    @Autowired ExpiredHoldReleaser releaser;
    @Autowired ShowAdminService admin;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookings;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;

    @BeforeEach
    void freshArena() {
        clock.reset();
        admin.resetShow(SHOW, 5);
    }

    private long firstSeatId() {
        return seats.findByShowIdOrderByIdAsc(SHOW).get(0).getId();
    }

    private HoldResult hold(StrategyType type, String user, Long seatId) {
        return orchestrator.hold(type, new HoldCommand(SHOW, seatId, user, 0));
    }

    @ParameterizedTest
    @EnumSource(StrategyType.class)
    void everyStrategyCanHoldAndConfirm(StrategyType type) {
        HoldResult h = hold(type, "alice", null);
        BookingView b = lifecycle.confirm(h.holdToken());

        assertThat(b.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seats.findById(h.seatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    void confirmIsIdempotent() {
        HoldResult h = hold(StrategyType.OPTIMISTIC, "alice", null);
        BookingView first = lifecycle.confirm(h.holdToken());
        BookingView second = lifecycle.confirm(h.holdToken());

        assertThat(second.bookingId()).isEqualTo(first.bookingId());
        assertThat(second.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void secondHoldOnSameSeatIsRejected() {
        long seatId = firstSeatId();
        hold(StrategyType.PESSIMISTIC, "alice", seatId);

        assertThatThrownBy(() -> hold(StrategyType.OPTIMISTIC, "bob", seatId))
                .isInstanceOf(TatkalException.class)
                .extracting(e -> ((TatkalException) e).code())
                .isEqualTo(ErrorCode.SEAT_UNAVAILABLE);
    }

    @Test
    void releaseReturnsSeatAndIsIdempotent() {
        HoldResult h = hold(StrategyType.OPTIMISTIC, "alice", null);
        lifecycle.release(h.holdToken());
        BookingView again = lifecycle.release(h.holdToken());

        assertThat(again.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seats.findById(h.seatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void cannotConfirmAfterTtl() {
        HoldResult h = hold(StrategyType.OPTIMISTIC, "alice", null);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThatThrownBy(() -> lifecycle.confirm(h.holdToken()))
                .extracting(e -> ((TatkalException) e).code())
                .isEqualTo(ErrorCode.HOLD_EXPIRED);
    }

    @Test
    void sweeperReleasesExpiredHoldsAndBumpsVersion() {
        HoldResult h = hold(StrategyType.OPTIMISTIC, "alice", null);
        long versionWhileHeld = seats.findById(h.seatId()).orElseThrow().getVersion();

        assertThat(releaser.releaseExpired(clock.instant())).isZero(); // not expired yet

        clock.advance(Duration.ofMinutes(6));
        assertThat(releaser.releaseExpired(clock.instant())).isEqualTo(1);

        Seat seat = seats.findById(h.seatId()).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getVersion()).isGreaterThan(versionWhileHeld);
        assertThat(bookings.findByHoldToken(h.holdToken()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);

        // running again is a no-op (idempotent)
        assertThat(releaser.releaseExpired(clock.instant())).isZero();
    }

    @Test
    void expiredHoldCanBeTakenOverBeforeSweeperRuns() {
        long seatId = firstSeatId();
        HoldResult alice = hold(StrategyType.OPTIMISTIC, "alice", seatId);
        clock.advance(Duration.ofMinutes(6)); // no sweeper run

        HoldResult bob = hold(StrategyType.PESSIMISTIC, "bob", seatId);

        assertThat(bob.seatId()).isEqualTo(seatId);
        assertThat(bookings.findByHoldToken(alice.holdToken()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThatThrownBy(() -> lifecycle.confirm(alice.holdToken())).isInstanceOf(TatkalException.class);
        assertThat(lifecycle.confirm(bob.holdToken()).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void soldOutWhenNoSeatsLeft() {
        for (int i = 0; i < 5; i++) {
            hold(StrategyType.SKIP_LOCKED, "u" + i, null);
        }
        assertThatThrownBy(() -> hold(StrategyType.SKIP_LOCKED, "late", null))
                .extracting(e -> ((TatkalException) e).code())
                .isEqualTo(ErrorCode.SOLD_OUT);
    }

    @Test
    void httpContract() throws Exception {
        long seatId = firstSeatId();
        String body = "{\"userId\":\"alice\",\"seatId\":" + seatId + "}";

        mvc.perform(post("/api/v1/optimistic/shows/{id}/holds", SHOW).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdToken").exists())
                .andExpect(jsonPath("$.strategy").value("OPTIMISTIC"));

        mvc.perform(post("/api/v1/pessimistic/shows/{id}/holds", SHOW).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"bob\",\"seatId\":" + seatId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

        mvc.perform(post("/api/v1/optimistic/shows/{id}/holds", SHOW).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
