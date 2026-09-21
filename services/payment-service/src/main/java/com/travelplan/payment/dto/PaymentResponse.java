package com.travelplan.payment.dto;

import com.travelplan.payment.entity.Payment;
import com.travelplan.payment.entity.PaymentProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for a payment resource.
 *
 * Intentionally omits {@code deleted_at}: that field is an internal
 * soft-delete implementation detail and must never be exposed over the API.
 */
public class PaymentResponse {

    private final UUID id;
    private final UUID userId;
    private final BigDecimal amount;
    private final String currency;
    private final String status;
    private final String externalReference;
    private final OffsetDateTime createdAt;
    private final PaymentProvider provider;
    private final UUID travelId;
    private final UUID subscriptionRef;

    private PaymentResponse(UUID id, UUID userId, BigDecimal amount, String currency, String status,
                             String externalReference, OffsetDateTime createdAt, PaymentProvider provider,
                             UUID travelId, UUID subscriptionRef) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.externalReference = externalReference;
        this.createdAt = createdAt;
        this.provider = provider;
        this.travelId = travelId;
        this.subscriptionRef = subscriptionRef;
    }

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getExternalReference(),
                payment.getCreatedAt(),
                payment.getProvider(),
                payment.getTravelId(),
                payment.getSubscriptionRef());
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

    public String getExternalReference() {
        return externalReference;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public PaymentProvider getProvider() {
        return provider;
    }

    public UUID getTravelId() {
        return travelId;
    }

    public UUID getSubscriptionRef() {
        return subscriptionRef;
    }
}
