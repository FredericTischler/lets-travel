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

    private TravelerSubscriptionResponse(UUID destinationId, String destinationName, String destinationCountry,
                                          LocalDate destinationStartDate, String status,
                                          OffsetDateTime subscribedAt, OffsetDateTime cancelledAt) {
        this.destinationId = destinationId;
        this.destinationName = destinationName;
        this.destinationCountry = destinationCountry;
        this.destinationStartDate = destinationStartDate;
        this.status = status;
        this.subscribedAt = subscribedAt;
        this.cancelledAt = cancelledAt;
    }

    public static TravelerSubscriptionResponse from(TravelerSubscriptionView view) {
        return new TravelerSubscriptionResponse(
                view.destinationId(), view.destinationName(), view.destinationCountry(),
                view.destinationStartDate(), view.status(), view.subscribedAt(), view.cancelledAt());
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
