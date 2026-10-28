package com.tatkal.hold;

/**
 * Strategy pattern: one concurrency technique per implementation. Controllers and the
 * Stampede Simulator only talk to this interface.
 *
 * tryOnce() is exactly ONE transaction. Retrying happens outside it, in HoldOrchestrator,
 * because a transaction that hit a conflict is rollback-only and must not be reused.
 */
public interface SeatHoldStrategy {

    StrategyType type();

    HoldResult tryOnce(HoldCommand command);
}
