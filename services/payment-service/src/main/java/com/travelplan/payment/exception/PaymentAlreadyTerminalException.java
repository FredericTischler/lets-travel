package com.travelplan.payment.exception;

import java.util.UUID;

/**
 * Thrown when a status transition is attempted on a payment whose current
 * status is already terminal (COMPLETED or FAILED). Terminal states are
 * immutable: not even a transition to the same value, and not a transition
 * between the two terminal values, is permitted.
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class PaymentAlreadyTerminalException extends RuntimeException {

    public PaymentAlreadyTerminalException(UUID id, String currentStatus) {
        super("Payment " + id + " is already in a terminal status (" + currentStatus
                + ") and cannot be transitioned again.");
    }
}