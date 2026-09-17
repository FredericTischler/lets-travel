package com.travelplan.payment.exception;

import java.util.UUID;

/**
 * Thrown when a payment is not found (absent or soft-deleted).
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID id) {
        super("Payment not found: " + id);
    }

    /**
     * Used when the lookup key is a provider-side external reference (a
     * Stripe PaymentIntent id or a PayPal Order id) rather than this
     * service's own payment id — see
     * {@link com.travelplan.payment.service.PayPalPaymentService#captureOrder}.
     */
    public PaymentNotFoundException(String externalReference) {
        super("Payment not found for external reference: " + externalReference);
    }
}