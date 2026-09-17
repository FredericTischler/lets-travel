package com.travelplan.identity.exception;

import java.util.UUID;

/**
 * Thrown when a status transition is attempted on a report whose current
 * status is already terminal (REVIEWED, DISMISSED or ACTIONED). Terminal
 * states are immutable: not even a transition to the same value, and not a
 * transition between two terminal values, is permitted — same rule as
 * payment-service's {@code PaymentAlreadyTerminalException}.
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class ReportAlreadyTerminalException extends RuntimeException {

    public ReportAlreadyTerminalException(UUID id, String currentStatus) {
        super("Report " + id + " is already in a terminal status (" + currentStatus
                + ") and cannot be transitioned again.");
    }
}
