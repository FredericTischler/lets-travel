package com.travelplan.travel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.travelplan.travel.repository.SubscriptionView;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for a single {@code SUBSCRIBED} relation — used by
 * {@code POST /destinations/{id}/subscriptions} (the created subscription)
 * and {@code GET /destinations/{id}/subscriptions} (the manager/admin
 * subscriber list, one row per traveler/status).
 *
 * <p>{@code status} is one of {@code ACTIVE}, {@code PENDING_PAYMENT},
 * {@code CANCELLED}, {@code EXPIRED} (docs/lets-travel-architecture-decisions.md
 * §4 addendum). {@code id}, {@code expiresAt}, {@code paymentId},
 * {@code amount}, {@code currency} are {@code null} on free/older
 * subscriptions. {@code payment} is only present in the response to the
 * subscribe call of a paid destination.</p>
 */
public class SubscriptionResponse {

    private final UUID id;
    private final UUID destinationId;
    private final UUID travelerId;
    private final String status;
    private final OffsetDateTime subscribedAt;
    private final OffsetDateTime cancelledAt;
    private final OffsetDateTime expiresAt;
    private final UUID paymentId;
    private final BigDecimal amount;
    private final String currency;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final PaymentCheckoutResponse payment;

    private SubscriptionResponse(SubscriptionView view, PaymentCheckoutResponse payment) {
        this.id = view.id();
        this.destinationId = view.destinationId();
        this.travelerId = view.travelerId();
        this.status = view.status();
        this.subscribedAt = view.subscribedAt();
        this.cancelledAt = view.cancelledAt();
        this.expiresAt = view.expiresAt();
        this.paymentId = view.paymentId();
        this.amount = view.amount();
        this.currency = view.currency();
        this.payment = payment;
    }

    public static SubscriptionResponse from(SubscriptionView view) {
        return new SubscriptionResponse(view, null);
    }

    /** Subscribe response of a paid destination: the subscription plus how to complete the payment. */
    public static SubscriptionResponse from(SubscriptionView view, PaymentCheckoutResponse payment) {
        return new SubscriptionResponse(view, payment);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDestinationId() {
        return destinationId;
    }

    public UUID getTravelerId() {
        return travelerId;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getSubscribedAt() {
        return subscribedAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentCheckoutResponse getPayment() {
        return payment;
    }
}
