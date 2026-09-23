package com.travelplan.payment.dto;

import com.travelplan.payment.entity.PaymentProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fields shared by every provider-specific payment-creation response
 * ({@link PayPalPaymentResponse}, {@link StripePaymentResponse}) — the same
 * nine values as {@link PaymentResponse} minus {@code externalReference}
 * (each subclass exposes the provider's own id under its own name —
 * {@code orderId}/{@code paymentIntentId} — plus a client-facing
 * secret/URL {@link PaymentResponse} never carries). Extracted here purely
 * to remove duplicated getter boilerplate across the two subclasses — same
 * public getter names as before, so the JSON response shape of each is
 * unchanged.
 */
public abstract class BasePaymentResponse {

    private final UUID id;
    private final UUID userId;
    private final BigDecimal amount;
    private final String currency;
    private final String status;
    private final PaymentProvider provider;
    private final OffsetDateTime createdAt;
    private final UUID travelId;
    private final UUID subscriptionRef;

    protected BasePaymentResponse(UUID id, UUID userId, BigDecimal amount, String currency, String status,
                                   PaymentProvider provider, OffsetDateTime createdAt, UUID travelId,
                                   UUID subscriptionRef) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.provider = provider;
        this.createdAt = createdAt;
        this.travelId = travelId;
        this.subscriptionRef = subscriptionRef;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public UUID getTravelId() {
        return travelId;
    }

    public UUID getSubscriptionRef() {
        return subscriptionRef;
    }
}
