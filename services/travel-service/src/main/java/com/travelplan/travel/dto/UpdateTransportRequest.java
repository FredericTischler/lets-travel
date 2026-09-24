package com.travelplan.travel.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/**
 * Request body for {@code PUT /destinations/{fromId}/transports/{transportId}}.
 *
 * Deliberately has no {@code toDestinationId}: a transport's endpoints never
 * change after creation — re-pointing a route is a delete-and-create, not an
 * edit of this one (same reasoning as why the id, not the endpoints, is what
 * update/delete address). Same validation split as
 * {@link CreateTransportRequest}: only structural completeness here, business
 * rules (allowed mode, positive duration) in {@code TransportService}.
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
