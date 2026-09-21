package com.travelplan.travel.dto;

import com.travelplan.travel.repository.FeedbackView;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for a single feedback, used by every feedback endpoint (the
 * creation response, the manager's per-destination quality-control list, the
 * traveler's own history, the admin list). Carries a destination summary so a
 * consumer never needs a second round trip per row.
 *
 * {@code comment} is plain text, never HTML — consumers must render it
 * escaped (docs/lets-travel-architecture-decisions.md §5 addendum).
 */
public class FeedbackResponse {

    private final UUID id;
    private final UUID travelerId;
    private final UUID destinationId;
    private final String destinationName;
    private final String destinationCountry;
    private final LocalDate destinationEndDate;
    private final int rating;
    private final String comment;
    private final OffsetDateTime createdAt;

    private FeedbackResponse(FeedbackView view) {
        this.id = view.id();
        this.travelerId = view.travelerId();
        this.destinationId = view.destinationId();
        this.destinationName = view.destinationName();
        this.destinationCountry = view.destinationCountry();
        this.destinationEndDate = view.destinationEndDate();
        this.rating = view.rating();
        this.comment = view.comment();
        this.createdAt = view.createdAt();
    }

    public static FeedbackResponse from(FeedbackView view) {
        return new FeedbackResponse(view);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTravelerId() {
        return travelerId;
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

    public LocalDate getDestinationEndDate() {
        return destinationEndDate;
    }

    public int getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
