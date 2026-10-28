package com.tatkal;

import com.tatkal.hold.HoldOutcome;
import com.tatkal.hold.StrategyType;
import com.tatkal.show.ShowAdminService;
import com.tatkal.stampede.StampedeMode;
import com.tatkal.stampede.StampedeReport;
import com.tatkal.stampede.StampedeRequest;
import com.tatkal.stampede.StampedeService;
import com.tatkal.support.AbstractPostgresIT;
import com.tatkal.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline claim, as a test: many threads, few seats, zero oversell for every safe strategy,
 * with the database safety index switched OFF so the locking strategy alone gets the credit.
 * And the control experiment: NAIVE does oversell.
 */
class StampedeIT extends AbstractPostgresIT {

    @Autowired StampedeService stampede;
    @Autowired ShowAdminService admin;
    @Autowired MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.reset();
    }

    static Stream<Arguments> safeStrategies() {
        return Stream.of(StrategyType.OPTIMISTIC, StrategyType.PESSIMISTIC, StrategyType.SKIP_LOCKED)
                .flatMap(s -> Stream.of(Arguments.of(s, StampedeMode.ANY_SEAT), Arguments.of(s, StampedeMode.SAME_SEAT)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("safeStrategies")
    void safeStrategiesNeverOversell(StrategyType strategy, StampedeMode mode) {
        int seats = 3;
        StampedeReport r = stampede.run(new StampedeRequest(strategy, mode, 200, seats, 2, false, false, null));

        assertThat(r.invariant().oversoldSeats()).as(r.verdict()).isZero();
        assertThat(r.invariant().holds()).as(r.verdict()).isTrue();
        int expected = mode == StampedeMode.SAME_SEAT ? 1 : seats;
        assertThat(r.outcomes().get(HoldOutcome.SUCCESS)).as(r.toString()).isEqualTo(expected);
        assertThat(r.outcomes().get(HoldOutcome.ERROR)).as(r.sampleErrors().toString()).isZero();
        assertThat(admin.isDbGuardPresent()).isTrue(); // safety net restored after the run
    }

    @Test
    void naiveStrategyOversells_controlExperiment() {
        StampedeReport r = stampede.run(new StampedeRequest(StrategyType.NAIVE, StampedeMode.ANY_SEAT,
                200, 3, 5, false, false, null));

        assertThat(r.invariant().oversoldSeats()).as(r.verdict()).isPositive();
        assertThat(r.invariant().holds()).isFalse();
        assertThat(admin.isDbGuardPresent()).isTrue();
    }

    @Test
    void databaseGuardStopsNaiveOversell() {
        StampedeReport r = stampede.run(new StampedeRequest(StrategyType.NAIVE, StampedeMode.ANY_SEAT,
                200, 3, 5, true, false, null));

        assertThat(r.invariant().oversoldSeats()).isZero();
        assertThat(r.outcomes().get(HoldOutcome.DB_GUARD_REJECTED)).isPositive();
    }

    @Test
    void worksWithVirtualThreads() {
        StampedeReport r = stampede.run(new StampedeRequest(StrategyType.SKIP_LOCKED, StampedeMode.ANY_SEAT,
                500, 5, 2, false, true, null));

        assertThat(r.invariant().holds()).as(r.verdict()).isTrue();
        assertThat(r.outcomes().get(HoldOutcome.SUCCESS)).isEqualTo(5);
    }
}
