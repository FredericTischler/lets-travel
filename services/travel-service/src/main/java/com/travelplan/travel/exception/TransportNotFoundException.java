package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when a transport addressed by {@code (fromId, transportId)} does not
 * exist, is already soft-deleted, or does not hang off {@code fromId}.
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class TransportNotFoundException extends RuntimeException {

    public TransportNotFoundException(UUID fromId, UUID transportId) {
        super("Transport not found: " + transportId + " (origin " + fromId + ")");
    }
}
