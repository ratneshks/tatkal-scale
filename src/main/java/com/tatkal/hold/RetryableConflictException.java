package com.tatkal.hold;

/**
 * Internal signal: "this attempt lost a race but seats may still be free, try again in a
 * fresh transaction". Never reaches the HTTP layer.
 */
public class RetryableConflictException extends RuntimeException {

    public RetryableConflictException(String message) {
        super(message, null, false, false); // no stack trace: this is control flow, thrown a lot
    }
}
