package com.tatkal.common;

/** A business failure with a stable error code; mapped to an HTTP ProblemDetail. */
public class TatkalException extends RuntimeException {

    private final ErrorCode code;

    public TatkalException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
