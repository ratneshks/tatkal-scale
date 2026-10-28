package com.tatkal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * One injectable clock for every "now" in the app (hold expiry, confirm, sweeper).
 * Tests swap it for a controllable clock to fast-forward past the 5-minute TTL.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
