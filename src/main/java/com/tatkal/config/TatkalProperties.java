package com.tatkal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Typed, validated-at-startup settings bound from the "tatkal" block of application.yml. */
@ConfigurationProperties(prefix = "tatkal")
public record TatkalProperties(Hold hold, Sweeper sweeper, Stampede stampede) {

    public record Hold(Duration ttl, int maxAttempts, Duration backoffBase, Duration backoffMax, Duration lockTimeout) {
    }

    public record Sweeper(Duration delay) {
    }

    public record Stampede(long showId, int maxThreads) {
    }
}
