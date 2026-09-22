package com.travelplan.travel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for one TRANSPORT hop: the relationship's own properties
 * (id, mode, durationMinutes, optional departureTime/arrivalTime) plus the
 * reachable target destination's public fields — never the raw
 * {@code Destination} entity, never {@code deletedAt}.
 *
 * Used as the response of {@code POST}/{@code PATCH}
 * {@code /destinations/{fromId}/transports[/{transportId}]} (a single hop)
 * and as each element of the list returned by
 * {@code GET /destinations/{id}/transports}.
 *
 * {@code id} (docs/lets-travel-architecture-decisions.md §11 addendum) is
 * the relationship's own application-assigned identifier, added after the
 * initial increment 2 shape — purely additive, needed so a single
 * {@code TRANSPORT} relationship can later be targeted for
 * {@code PATCH}/{@code DELETE}.
 */
public class TransportResponse {

    private final UUID id;
    private final String mode;
    private final int durationMinutes;
    private final OffsetDateTime departureTime;
    private final OffsetDateTime arrivalTime;
    private final UUID destinationId;
    private final String destinationName;
    private final String destinationCountry;

    public TransportResponse(UUID id, String mode, int durationMinutes, OffsetDateTime departureTime,
                              OffsetDateTime arrivalTime, UUID destinationId,
                              String destinationName, String destinationCountry) {
        this.id = id;
        this.mode = mode;
        this.durationMinutes = durationMinutes;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.destinationId = destinationId;
        this.destinationName = destinationName;
        this.destinationCountry = destinationCountry;
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
        return destinationId;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public String getDestinationCountry() {
        return destinationCountry;
    }
}
