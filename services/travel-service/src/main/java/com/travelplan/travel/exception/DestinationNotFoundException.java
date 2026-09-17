package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when a destination is not found (absent or soft-deleted).
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class DestinationNotFoundException extends RuntimeException {

    public DestinationNotFoundException(UUID id) {
        super("Destination not found: " + id);
    }
}