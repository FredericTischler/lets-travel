package com.travelplan.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request body for {@code POST /destinations/{fromId}/transports}.
 *
 * Bean Validation here only guarantees structural completeness (both ids
 * present, mode non-blank). The actual business rules — mode must be one of
 * the five allowed values, durationMinutes must be positive, fromId must
 * differ from toDestinationId — are deliberately NOT annotation-based: they
 * must each map to HTTP 400 with a specific message, which only
 * {@link com.travelplan.travel.service.TransportService} can resolve — same
 * approach as {@code UpdateStatusRequest} in payment-service.
 *
 * {@code departureTime}/{@code arrivalTime} are optional schedule details
 * (both nullable, no validation rule between them — kept minimal, same
 * scope as the rest of increment 2).
 */
public class CreateTransportRequest {

    @NotNull(message = "must not be null")
    private UUID toDestinationId;

    @NotBlank(message = "must not be blank")
    private String mode;

    private int durationMinutes;

    private OffsetDateTime departureTime;

    private OffsetDateTime arrivalTime;

    public CreateTransportRequest() {
        // required for Jackson deserialization
    }

    public UUID getToDestinationId() {
        return toDestinationId;
    }

    public void setToDestinationId(UUID toDestinationId) {
        this.toDestinationId = toDestinationId;
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