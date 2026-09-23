package com.travelplan.payment.dto;

/**
 * Request body for {@code POST /payments/stripe}.
 *
 * Validated by {@code @Valid} in the controller — same constraints as
 * {@link CreateManualPaymentRequest}, since a Stripe PaymentIntent is created
 * from exactly the same three inputs (owner, amount, currency), inherited
 * from {@link CreatePaymentRequest}; the Stripe PaymentIntent id itself is
 * produced server-side by {@link com.travelplan.payment.service.StripePaymentService},
 * never supplied by the client.
 */
public class CreateStripePaymentRequest extends CreatePaymentRequest {

    public CreateStripePaymentRequest() {
        // required for Jackson deserialization
    }
}
