package com.tatkal.stampede;

import com.tatkal.hold.HoldOutcome;
import com.tatkal.hold.StrategyType;

import java.util.List;
import java.util.Map;

public record StampedeReport(
        String verdict,
        StrategyType strategy,
        StampedeMode mode,
        int threads,
        int seats,
        int thinkTimeMs,
        boolean dbGuard,
        boolean virtualThreads,
        int dbPoolSize,
        Map<HoldOutcome, Integer> outcomes,
        long totalRetries,
        Invariant invariant,
        Latency latency,
        double wallMs,
        double throughputPerSec,
        double avgConnectionAcquireMs,
        List<String> sampleErrors) {

    /**
     * Checked against the DATABASE after the run, not against Java counters.
     *
     * @param activeBookings PENDING_PAYMENT + CONFIRMED bookings for the show
     * @param oversoldSeats  seats with more than one active booking
     * @param extraBookings  bookings beyond the first on those seats (tickets sold that don't exist)
     */
    public record Invariant(int activeBookings, int oversoldSeats, int extraBookings,
                            boolean successesMatchBookings, boolean holds) {
    }

    public record Latency(double p50Ms, double p95Ms, double p99Ms, double maxMs) {
    }
}
