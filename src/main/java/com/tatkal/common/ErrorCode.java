package com.tatkal.common;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes returned in every ProblemDetail response. */
public enum ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT),
    SOLD_OUT(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),   // optimistic retries exhausted
    DB_GUARD_REJECTED(HttpStatus.CONFLICT),         // partial unique index blocked a duplicate booking
    LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),   // pessimistic lock wait exceeded lock_timeout
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND),
    HOLD_EXPIRED(HttpStatus.GONE),
    HOLD_NOT_ACTIVE(HttpStatus.CONFLICT),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT),
    STAMPEDE_ALREADY_RUNNING(HttpStatus.CONFLICT),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
