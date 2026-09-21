package com.travelplan.travel.exception;

/**
 * Thrown when payment-service could not create the payment for a paid
 * subscription (unreachable, timed out, or it answered non-2xx). Mapped to
 * HTTP 502 by {@link GlobalExceptionHandler}. The pending subscription created
 * just before the call has already been cancelled when this reaches the caller
 * — the traveler can simply retry.
 */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
