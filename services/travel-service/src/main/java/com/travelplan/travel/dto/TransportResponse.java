package com.travelplan.travel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for one TRANSPORT hop: the relationship's own properties
 * (mode, durationMinutes, optional departureTime/arrivalTime) plus the
 * reachable target destination's public fields — never the raw
 * {@code Destination} entity, never {@code deletedAt}.
 *
 * Used both as the response of {@code POST /destinations/{fromId}/transports}
 * (the single created hop) and as each element of the list returned by
 * {@code GET /destinations/{id}/transports}.
 */
public class TransportResponse {

    private final String mode;
    private final int durationMinutes;
    private final OffsetDateTime departureTime;
    private final OffsetDateTime arrivalTime;
    private final UUID destinationId;
    private final String destinationName;
    private final String destinationCountry;

    public TransportResponse(String mode, int durationMinutes, OffsetDateTime departureTime,
                              OffsetDateTime arrivalTime, UUID destinationId,
                              String destinationName, String destinationCountry) {
        this.mode = mode;
        this.durationMinutes = durationMinutes;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.destinationId = destinationId;
        this.destinationName = destinationName;
        this.destinationCountry = destinationCountry;
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