package com.travelplan.travel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for one TRANSPORT hop: the relationship's own properties
 * (id, mode, durationMinutes, optional departureTime/arrivalTime) plus the
 * reachable target destination's public fields — never the raw
 * {@code Destination} entity, never {@code deletedAt}.
 *
 * Used as the response of {@code POST}/{@code PUT .../transports/{id}} (the
 * single created/updated hop), as each element of the list returned by
 * {@code GET /destinations/{id}/transports}, and as each hop of
 * {@link RouteResponse}. {@code id} is this project's app-assigned UUID
 * (same convention as {@code Destination.id}), never a Neo4j-internal id —
 * it is what {@code PUT}/{@code DELETE /destinations/{fromId}/transports/{id}}
 * address.
 */
public class TransportResponse {

    /** The reachable target's public fields, grouped only to keep the constructor under 8 parameters. */
    public record Target(UUID id, String name, String country) {
    }

    private final UUID id;
    private final String mode;
    private final int durationMinutes;
    private final OffsetDateTime departureTime;
    private final OffsetDateTime arrivalTime;
    private final Target destination;

    public TransportResponse(UUID id, String mode, int durationMinutes, OffsetDateTime departureTime,
                              OffsetDateTime arrivalTime, Target destination) {
        this.id = id;
        this.mode = mode;
        this.durationMinutes = durationMinutes;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.destination = destination;
    }

    public UUID getId() {
        return id;
    }

    public String getMode() {
        return mode;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public OffsetDateTime getDepartureTime() {
        return departureTime;
    }

    public OffsetDateTime getArrivalTime() {
        return arrivalTime;
    }

    public UUID getDestinationId() {
        return destination.id();
    }

    public String getDestinationName() {
        return destination.name();
    }

    public String getDestinationCountry() {
        return destination.country();
    }
}
