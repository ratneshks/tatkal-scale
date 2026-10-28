package com.tatkal.stampede;

import com.tatkal.hold.StrategyType;

import java.util.List;

/**
 * All fields optional; sensible defaults give "500 threads fight over the last 5 tickets".
 *
 * @param dbGuard        keep the partial unique index on during the run. Default false, so the
 *                       zero-oversell result is earned by the locking strategy alone, not by the index.
 * @param virtualThreads use Java 21 virtual threads instead of a fixed platform-thread pool
 * @param strategies     only used by /compare: which strategies to run back to back
 */
public record StampedeRequest(StrategyType strategy,
                              StampedeMode mode,
                              Integer threads,
                              Integer seats,
                              Integer thinkTimeMs,
                              Boolean dbGuard,
                              Boolean virtualThreads,
                              List<StrategyType> strategies) {

    public StrategyType strategyOrDefault() {
        return strategy == null ? StrategyType.OPTIMISTIC : strategy;
    }

    public StampedeMode modeOrDefault() {
        return mode == null ? StampedeMode.ANY_SEAT : mode;
    }

    public int threadsOrDefault() {
        return threads == null ? 500 : threads;
    }

    public int seatsOrDefault() {
        return seats == null ? 5 : seats;
    }

    public int thinkTimeOrDefault() {
        return thinkTimeMs == null ? 2 : thinkTimeMs;
    }

    public boolean dbGuardOrDefault() {
        return dbGuard != null && dbGuard;
    }

    public boolean virtualThreadsOrDefault() {
        return virtualThreads != null && virtualThreads;
    }

    public List<StrategyType> strategiesOrDefault() {
        return strategies == null || strategies.isEmpty() ? List.of(StrategyType.values()) : strategies;
    }

    public StampedeRequest withStrategy(StrategyType s) {
        return new StampedeRequest(s, mode, threads, seats, thinkTimeMs, dbGuard, virtualThreads, null);
    }
}
