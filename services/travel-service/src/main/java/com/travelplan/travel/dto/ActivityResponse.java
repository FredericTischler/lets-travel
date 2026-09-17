package com.travelplan.travel.dto;

import java.util.UUID;

/**
 * API representation of one activity belonging to a destination, as returned
 * inside {@link DestinationResponse}.
 */
public class ActivityResponse {

    private final UUID id;
    private final String name;

    public ActivityResponse(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
