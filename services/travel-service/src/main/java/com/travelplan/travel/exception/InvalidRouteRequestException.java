package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when a {@code GET /destinations/{fromId}/routes/{toId}} request is
 * malformed regardless of what exists in the graph — currently only
 * {@code fromId} equal to {@code toId}. Mapped to HTTP 400 by
 * {@link GlobalExceptionHandler}.
 */
public class InvalidRouteRequestException extends RuntimeException {

    private InvalidRouteRequestException(String message) {
        super(message);
    }

    public static InvalidRouteRequestException sameOriginAndTarget(UUID id) {
        return new InvalidRouteRequestException("A route needs two different destinations, got the same id twice: " + id);
    }
}
