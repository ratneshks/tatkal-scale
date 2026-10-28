package com.tatkal.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/** Turns exceptions into RFC 9457 ProblemDetail JSON with a stable "code" field. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TatkalException.class)
    public ProblemDetail handleTatkal(TatkalException e) {
        return problem(e.code().status(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.name(), detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.name(), e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(code);
        pd.setType(URI.create("urn:tatkal:error:" + code.toLowerCase()));
        pd.setProperty("code", code);
        return pd;
    }
}
