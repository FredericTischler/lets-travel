package com.travelplan.payment.dto;

import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for {@code POST /payments/stripe}.
 *
 * Extends the standard payment fields (see {@link PaymentResponse}) with
 * {@code clientSecret} — the value the Stripe.js/Stripe Elements client-side
 * SDK needs to confirm the PaymentIntent (standard Stripe PaymentIntent
 * flow). {@code clientSecret} is only ever returned once, at creation time —
 * it is never persisted (only the PaymentIntent id is, as
 * {@code externalReference}) and is not retrievable again via
 * {@code GET /payments/{id}}.
 */
public class StripePaymentResponse {

    private final UUID id;
    private final UUID userId;
    private final BigDecimal amount;
    private final String currency;
    private final String status;
    private final PaymentProvider provider;
    private final String paymentIntentId;
    private final String clientSecret;
    private final OffsetDateTime createdAt;

    private StripePaymentResponse(UUID id, UUID userId, BigDecimal amount, String currency, String status,
                                   PaymentProvider provider, String paymentIntentId, String clientSecret,
                                   OffsetDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.provider = provider;
        this.paymentIntentId = paymentIntentId;
        this.clientSecret = clientSecret;
        this.createdAt = createdAt;
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
                payment.getCreatedAt());
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public PaymentProvider getProvider() {
        return provider;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
