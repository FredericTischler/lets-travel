package com.travelplan.travel.exception;

import java.util.Set;
import java.util.UUID;

/**
 * Thrown when a {@code POST /destinations/{fromId}/transports} request
 * violates one of the increment 2 creation rules: self-loop (fromId equals
 * toDestinationId), an unsupported {@code mode}, or a non-positive
 * {@code durationMinutes}.
 *
 * All three cases map to HTTP 400 via {@link GlobalExceptionHandler}; they
 * are grouped in a single exception type (rather than one class per rule, as
 * in payment-service's status transition exceptions) because none of them
 * need a distinct HTTP status from one another — only the message differs.
 */
public class InvalidTransportRequestException extends RuntimeException {

    private InvalidTransportRequestException(String message) {
        super(message);
    }

    public static InvalidTransportRequestException selfLoop(UUID destinationId) {
        return new InvalidTransportRequestException(
                "A transport cannot connect a destination to itself: " + destinationId);
    }

    public static InvalidTransportRequestException invalidMode(String requestedMode, Set<String> allowedModes) {
        return new InvalidTransportRequestException(
                "Invalid transport mode: " + requestedMode + ". Allowed values are " + allowedModes);
    }

    public static InvalidTransportRequestException invalidDuration(int requestedDurationMinutes) {
        return new InvalidTransportRequestException(
                "durationMinutes must be a positive integer, got: " + requestedDurationMinutes);
    }
}