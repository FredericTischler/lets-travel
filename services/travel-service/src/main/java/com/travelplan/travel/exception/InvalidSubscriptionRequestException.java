package com.travelplan.travel.exception;

/**
 * Thrown when a subscribe request is well-formed JSON but incomplete for the
 * destination it targets — today only: a paid destination with no payment
 * {@code provider} chosen. Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class InvalidSubscriptionRequestException extends RuntimeException {

    private InvalidSubscriptionRequestException(String message) {
        super(message);
    }

    public static InvalidSubscriptionRequestException providerRequired() {
        return new InvalidSubscriptionRequestException(
                "This destination has a price: a payment provider (MANUAL, STRIPE or PAYPAL) is required");
    }
}
