package com.travelplan.travel.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * One accommodation entry within {@link CreateDestinationRequest} or
 * {@link UpdateDestinationRequest}.
 *
 * {@code checkIn}/{@code checkOut} are optional: omit them if the stay spans
 * the whole destination visit. If both are given, {@code checkOut} must not
 * be before {@code checkIn} — enforced in {@code DestinationService}, not
 * here (same "business rule in the service" approach as elsewhere in this
 * package).
 */
public class AccommodationRequest {

    @NotBlank(message = "must not be blank")
    private String name;

    @NotBlank(message = "must not be blank")
    private String type;

    private LocalDate checkIn;

    private LocalDate checkOut;

    public AccommodationRequest() {
        // required for Jackson deserialization
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public void setCheckIn(LocalDate checkIn) {
        this.checkIn = checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public void setCheckOut(LocalDate checkOut) {
        this.checkOut = checkOut;
    }
}
