package com.travelplan.payment.exception;

/**
 * Thrown by {@link com.travelplan.payment.service.StripePaymentService#handleWebhookEvent}
 * when a {@code POST /webhooks/stripe} request's {@code Stripe-Signature}
 * header does not verify against the configured webhook signing secret
 * (missing header, tampered payload, wrong/expired secret, timestamp outside
 * tolerance, etc.).
 *
 * Mapped to HTTP 400 Bad Request by {@link GlobalExceptionHandler} — this is
 * a request-shape problem (an unverifiable caller), not a lookup failure.
 */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException(String provider, Throwable cause) {
        super(provider + " webhook signature verification failed: " + cause.getMessage(), cause);
    }
}
