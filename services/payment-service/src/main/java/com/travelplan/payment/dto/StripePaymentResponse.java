package com.travelplan.payment.dto;

import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for {@code POST /payments/stripe}.
 *
 * Extends the standard payment fields (see {@link BasePaymentResponse}/
 * {@link PaymentResponse}) with {@code clientSecret} — the value the
 * Stripe.js/Stripe Elements client-side SDK needs to confirm the
 * PaymentIntent (standard Stripe PaymentIntent flow). {@code clientSecret}
 * is only ever returned once, at creation time — it is never persisted
 * (only the PaymentIntent id is, as {@code externalReference}) and is not
 * retrievable again via {@code GET /payments/{id}}.
 */
public class StripePaymentResponse extends BasePaymentResponse {

    private final String paymentIntentId;
    private final String clientSecret;

    private StripePaymentResponse(UUID id, UUID userId, BigDecimal amount, String currency, String status,
                                   PaymentProvider provider, String paymentIntentId, String clientSecret,
                                   OffsetDateTime createdAt, UUID travelId, UUID subscriptionRef) {
        super(id, userId, amount, currency, status, provider, createdAt, travelId, subscriptionRef);
        this.paymentIntentId = paymentIntentId;
        this.clientSecret = clientSecret;
    }

    public static StripePaymentResponse from(Payment payment, String clientSecret) {
        return new StripePaymentResponse(
                payment.getId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getProvider(),
                payment.getExternalReference(),
                clientSecret,
                payment.getCreatedAt(),
                payment.getTravelId(),
                payment.getSubscriptionRef());
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public String getClientSecret() {
        return clientSecret;
    }
}
