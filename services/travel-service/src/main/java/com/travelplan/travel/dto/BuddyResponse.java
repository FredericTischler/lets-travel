package com.travelplan.travel.dto;

import java.util.UUID;

/**
 * One element of {@code GET /destinations/{id}/buddies}
 * (docs/lets-travel-architecture-decisions.md §12): a UUID only — never a
 * name or email, which stay in identity-service (same privacy line as the
 * traveler id a manager sees on a report, §5ter.4).
 */
public class BuddyResponse {

    private final UUID travelerId;

    public BuddyResponse(UUID travelerId) {
        this.travelerId = travelerId;
    }

    public UUID getTravelerId() {
        return travelerId;
    }
}
