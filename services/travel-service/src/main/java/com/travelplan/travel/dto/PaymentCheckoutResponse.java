package com.travelplan.travel.dto;

import java.util.UUID;

/**
 * What the traveler needs to complete a payment, echoed from payment-service's
 * create response and returned once, in the {@code POST .../subscriptions}
 * response for a paid destination: the payment id, its provider, and the
 * provider-specific continuation — Stripe's {@code clientSecret} (to confirm
 * the PaymentIntent client-side) or PayPal's {@code approveUrl} (where to send
 * the payer). Both are {@code null} for a {@code MANUAL} payment, which an
 * administrator confirms out of band.
 *
 * Neither value is stored by travel-service.
 */
public record PaymentCheckoutResponse(UUID paymentId, PaymentProvider provider, String status,
                                       String clientSecret, String approveUrl) {
}
