package com.tatkal.seat;

import com.tatkal.common.ErrorCode;
import com.tatkal.common.TatkalException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests: no Spring, no database. Every legal and illegal transition. */
class SeatStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    @Test
    void availableSeatCanBeHeld() {
        Seat seat = new Seat(1L, "A-1");
        UUID token = seat.hold("alice", T0, TTL);

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.getHeldBy()).isEqualTo("alice");
        assertThat(seat.getHoldToken()).isEqualTo(token);
        assertThat(seat.getHoldExpiresAt()).isEqualTo(T0.plus(TTL));
    }

    @Test
    void heldSeatCannotBeHeldAgainBeforeExpiry() {
        Seat seat = new Seat(1L, "A-1");
        seat.hold("alice", T0, TTL);

        assertThatThrownBy(() -> seat.hold("bob", T0.plusSeconds(60), TTL))
                .isInstanceOf(TatkalException.class)
                .extracting(e -> ((TatkalException) e).code())
                .isEqualTo(ErrorCode.SEAT_UNAVAILABLE);
    }

    @Test
    void expiredHoldCanBeTakenOver_lazyExpiry() {
        Seat seat = new Seat(1L, "A-1");
        UUID aliceToken = seat.hold("alice", T0, TTL);

        Instant later = T0.plus(TTL).plusSeconds(1);
        assertThat(seat.isHoldableAt(later)).isTrue();
        UUID bobToken = seat.hold("bob", later, TTL);

        assertThat(bobToken).isNotEqualTo(aliceToken);
        assertThat(seat.getHeldBy()).isEqualTo("bob");
    }

    @Test
    void holderCanConfirmBeforeExpiry() {
        Seat seat = new Seat(1L, "A-1");
        UUID token = seat.hold("alice", T0, TTL);
        seat.confirm(token, T0.plusSeconds(10));

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(seat.isHoldableAt(T0.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    void cannotConfirmAfterExpiry() {
        Seat seat = new Seat(1L, "A-1");
        UUID token = seat.hold("alice", T0, TTL);

        assertThatThrownBy(() -> seat.confirm(token, T0.plus(TTL)))
                .extracting(e -> ((TatkalException) e).code())
                .isEqualTo(ErrorCode.HOLD_EXPIRED);
    }

    @Test
    void cannotConfirmWithSomeoneElsesToken() {
        Seat seat = new Seat(1L, "A-1");
        seat.hold("alice", T0, TTL);

        assertThatThrownBy(() -> seat.confirm(UUID.randomUUID(), T0.plusSeconds(1)))
                .isInstanceOf(TatkalException.class);
    }

    @Test
    void cannotConfirmAnAvailableSeat() {
        Seat seat = new Seat(1L, "A-1");
        assertThatThrownBy(() -> seat.confirm(UUID.randomUUID(), T0)).isInstanceOf(TatkalException.class);
    }

    @Test
    void releaseReturnsSeatToPool() {
        Seat seat = new Seat(1L, "A-1");
        UUID token = seat.hold("alice", T0, TTL);
        seat.release(token);

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getHoldToken()).isNull();
        assertThat(seat.getHeldBy()).isNull();
    }

    @Test
    void bookedSeatIsTerminal() {
        Seat seat = new Seat(1L, "A-1");
        UUID token = seat.hold("alice", T0, TTL);
        seat.confirm(token, T0);

        assertThatThrownBy(() -> seat.hold("bob", T0, TTL)).isInstanceOf(TatkalException.class);
        assertThatThrownBy(() -> seat.release(token)).isInstanceOf(TatkalException.class);
    }
}
