package com.travelplan.travel.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/**
 * Request body for {@code PATCH /destinations/{fromId}/transports/{transportId}}
 * (docs/lets-travel-architecture-decisions.md §11 addendum).
 *
 * Updates only the relationship's own attributes — {@code mode},
 * {@code durationMinutes}, {@code departureTime}, {@code arrivalTime} — never
 * the origin or the target, which would effectively move the link rather
 * than correct it.
 *
 * Same validation split as {@link CreateTransportRequest}: Bean Validation
 * here only guarantees {@code mode} is non-blank; the actual business rules
 * (mode must be one of the five allowed values, durationMinutes must be
 * positive) are resolved by
 * {@link com.travelplan.travel.service.TransportService} so each can map to
 * a specific 400 message — same rules as {@code create}, reused as-is.
 */
public class UpdateTransportRequest {

    @NotBlank(message = "must not be blank")
    private String mode;

    private int durationMinutes;

    private OffsetDateTime departureTime;

    private OffsetDateTime arrivalTime;

    public UpdateTransportRequest() {
        // required for Jackson deserialization
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public OffsetDateTime getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(OffsetDateTime departureTime) {
        this.departureTime = departureTime;
    }

    public OffsetDateTime getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(OffsetDateTime arrivalTime) {
        this.arrivalTime = arrivalTime;
    }
}
