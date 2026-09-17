package com.travelplan.identity.exception;

/**
 * Thrown when a status transition targets a value outside
 * {REVIEWED, DISMISSED, ACTIONED}. OPEN is never a valid target: it is
 * exclusively the initial state set at creation, never a transition
 * destination — same rule as payment-service's
 * {@code InvalidStatusValueException} for PENDING.
 * Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class InvalidReportStatusValueException extends RuntimeException {

    public InvalidReportStatusValueException(String requestedStatus) {
        super("Invalid target status: " + requestedStatus
                + ". Allowed target values are REVIEWED, DISMISSED or ACTIONED.");
    }
}
