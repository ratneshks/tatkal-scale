package com.tatkal.hold;

import java.util.Arrays;

public enum StrategyType {
    /** No locking at all. Exists only to prove the oversell problem is real. */
    NAIVE("naive"),
    /** JPA @Version: detect conflicts at write time, retry the loser. */
    OPTIMISTIC("optimistic"),
    /** SELECT ... FOR UPDATE: block other writers until commit. */
    PESSIMISTIC("pessimistic"),
    /** SELECT ... FOR UPDATE SKIP LOCKED: take any seat no one else is touching. */
    SKIP_LOCKED("skip-locked");

    private final String path;

    StrategyType(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static StrategyType fromPath(String value) {
        return Arrays.stream(values())
                .filter(t -> t.path.equalsIgnoreCase(value) || t.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + value));
    }
}
