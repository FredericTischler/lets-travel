package com.travelplan.travel.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /destinations/{id}/subscriptions/buddy-visibility}
 * (docs/lets-travel-architecture-decisions.md §12): the caller's own opt-in
 * flag to appear in {@code GET /destinations/{id}/buddies}. Off by default.
 */
public class BuddyVisibilityRequest {

    @NotNull
    private Boolean visible;

    public BuddyVisibilityRequest() {
        // required for Jackson deserialization
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }
}
