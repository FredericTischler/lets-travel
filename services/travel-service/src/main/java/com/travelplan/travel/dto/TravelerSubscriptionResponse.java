package com.travelplan.travel.dto;

import com.travelplan.travel.repository.TravelerSubscriptionView;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for {@code GET /travelers/me/subscriptions} — a traveler's
 * own subscription history. Unlike {@link SubscriptionResponse} (used where
 * the destination is already known from the request path), this includes a
 * destination summary so the personal stats page (sujet: "past travel
 * participation", "subscription cancellations") does not need a second
 * round trip per row.
 */
public class TravelerSubscriptionResponse {

    private final UUID destinationId;
    private final String destinationName;
    private final String destinationCountry;
    private final LocalDate destinationStartDate;
    private final String status;
    private final OffsetDateTime subscribedAt;
    private final OffsetDateTime cancelledAt;
    private final UUID subscriptionId;
    private final UUID paymentId;
    private final OffsetDateTime expiresAt;

    private TravelerSubscriptionResponse(TravelerSubscriptionView view) {
        this.destinationId = view.destinationId();
        this.destinationName = view.destinationName();
        this.destinationCountry = view.destinationCountry();
        this.destinationStartDate = view.destinationStartDate();
        this.status = view.status();
        this.subscribedAt = view.subscribedAt();
        this.cancelledAt = view.cancelledAt();
        this.subscriptionId = view.subscriptionId();
        this.paymentId = view.paymentId();
        this.expiresAt = view.expiresAt();
    }

    public static TravelerSubscriptionResponse from(TravelerSubscriptionView view) {
        return new TravelerSubscriptionResponse(view);
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public UUID getDestinationId() {
        return destinationId;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public String getDestinationCountry() {
        return destinationCountry;
    }

    public LocalDate getDestinationStartDate() {
        return destinationStartDate;
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
}
