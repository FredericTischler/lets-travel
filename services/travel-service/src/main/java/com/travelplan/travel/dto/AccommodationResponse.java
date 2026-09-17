package com.travelplan.travel.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * API representation of one accommodation belonging to a destination, as
 * returned inside {@link DestinationResponse}.
 */
public class AccommodationResponse {

    private final UUID id;
    private final String name;
    private final String type;
    private final LocalDate checkIn;
    private final LocalDate checkOut;

    public AccommodationResponse(UUID id, String name, String type, LocalDate checkIn, LocalDate checkOut) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }
}
