package com.travelplan.payment.exception;

/**
 * Thrown by {@link com.travelplan.payment.service.StripePaymentService} or
 * {@link com.travelplan.payment.service.PayPalPaymentService} when the
 * external provider's API call fails (network error, rejected credentials,
 * provider-side validation error, etc.).
 *
 * Distinct from every other exception in this package: the failure did not
 * originate in this service, so 502 Bad Gateway is the correct status — this
 * service acted correctly as a client of an upstream dependency that itself
 * failed or refused the call.
 */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String provider, Throwable cause) {
        super(provider + " payment provider call failed: " + cause.getMessage(), cause);
    }
}
