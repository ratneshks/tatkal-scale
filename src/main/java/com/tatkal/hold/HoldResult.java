package com.tatkal.hold;

import java.time.Instant;
import java.util.UUID;

public record HoldResult(UUID holdToken,
                         long seatId,
                         String seatNo,
                         String userId,
                         Instant expiresAt,
                         StrategyType strategy,
                         int attempts) {

    public HoldResult withAttempts(int n) {
        return new HoldResult(holdToken, seatId, seatNo, userId, expiresAt, strategy, n);
    }
}
