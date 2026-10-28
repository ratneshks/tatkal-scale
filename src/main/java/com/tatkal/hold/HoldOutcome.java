package com.tatkal.hold;

/** How one hold request ended. The Stampede Simulator counts these. */
public enum HoldOutcome {
    SUCCESS,
    /** Clean "no": the seat or the whole show is taken. The correct answer for losers. */
    SOLD_OUT,
    /** Kept losing optimistic races until the retry budget ran out. */
    RETRIES_EXHAUSTED,
    /** Waited longer than lock_timeout for a pessimistic lock (or lost a deadlock). */
    LOCK_TIMEOUT,
    /** Java logic let it through but the partial unique index refused a 2nd live booking. */
    DB_GUARD_REJECTED,
    ERROR
}
