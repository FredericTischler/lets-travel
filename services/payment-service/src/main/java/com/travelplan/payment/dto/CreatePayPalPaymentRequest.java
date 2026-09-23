package com.travelplan.payment.dto;

/**
 * Request body for {@code POST /payments/paypal}.
 *
 * Validated by {@code @Valid} in the controller — same constraints as
 * {@link CreateManualPaymentRequest}, since a PayPal Order is created from
 * exactly the same three inputs (owner, amount, currency), inherited from
 * {@link CreatePaymentRequest}; the PayPal Order id itself is produced
 * server-side by {@link com.travelplan.payment.service.PayPalPaymentService},
 * never supplied by the client.
 */
public class CreatePayPalPaymentRequest extends CreatePaymentRequest {

    public CreatePayPalPaymentRequest() {
        // required for Jackson deserialization
    }
}
