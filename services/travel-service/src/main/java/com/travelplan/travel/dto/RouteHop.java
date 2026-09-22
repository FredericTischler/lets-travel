package com.travelplan.travel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One leg of a multi-destination itinerary
 * (docs/lets-travel-architecture-decisions.md §11): the destination reached
 * at this hop, plus the {@code TRANSPORT} relationship's own attributes used
 * to reach it from the previous hop (or the origin, for the first hop).
 *
 * Same field shape as {@link TransportResponse} (minus {@code id}, which
 * identifies a single relationship for {@code PATCH}/{@code DELETE} and has
 * no meaning for a multi-hop itinerary) — kept as its own type rather than
 * reused, since the two responses answer different questions (one hop vs. an
 * ordered path) and may evolve independently.
 */
public class RouteHop {

    private final UUID destinationId;
    private final String destinationName;
    private final String destinationCountry;
    private final String mode;
    private final int durationMinutes;
    private final OffsetDateTime departureTime;
    private final OffsetDateTime arrivalTime;

    public RouteHop(UUID destinationId, String destinationName, String destinationCountry, String mode,
                     int durationMinutes, OffsetDateTime departureTime, OffsetDateTime arrivalTime) {
        this.destinationId = destinationId;
        this.destinationName = destinationName;
        this.destinationCountry = destinationCountry;
        this.mode = mode;
        this.durationMinutes = durationMinutes;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
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
}
