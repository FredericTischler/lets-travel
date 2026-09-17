package com.travelplan.identity.exception;

/**
 * Thrown when {@code POST /reports} arrives with {@code reportedUserId}
 * equal to the caller's own id (resolved from the token, never trusted from
 * the request body). A user cannot report themselves.
 * Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class SelfReportException extends RuntimeException {

    public SelfReportException() {
        super("A user cannot report themselves");
    }
}
