package com.travelplan.payment.exception;

/**
 * Thrown when a status transition targets a value outside {COMPLETED, FAILED}.
 * PENDING is never a valid target: it is exclusively the initial state set at
 * creation, never a transition destination.
 * Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class InvalidStatusValueException extends RuntimeException {

    public InvalidStatusValueException(String requestedStatus) {
        super("Invalid target status: " + requestedStatus
                + ". Allowed target values are COMPLETED or FAILED.");
    }
}