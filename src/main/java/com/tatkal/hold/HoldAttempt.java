package com.tatkal.hold;

import com.tatkal.common.ErrorCode;

/** Result of a hold request including failures, without throwing. Used by the simulator. */
public record HoldAttempt(HoldOutcome outcome, HoldResult result, int attempts, ErrorCode errorCode, String message) {

    static HoldAttempt success(HoldResult result) {
        return new HoldAttempt(HoldOutcome.SUCCESS, result, result.attempts(), null, null);
    }

    static HoldAttempt failure(HoldOutcome outcome, int attempts, ErrorCode code, String message) {
        return new HoldAttempt(outcome, null, attempts, code, message);
    }

    public int retries() {
        return Math.max(0, attempts - 1);
    }
}
