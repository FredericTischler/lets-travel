package com.travelplan.travel.exception;

import java.util.UUID;

/** An identical active transport (same endpoints, mode and departure) already exists — maps to 409. */
public class TransportConflictException extends RuntimeException {

    public TransportConflictException(String message) {
        super(message);
    }

    public static TransportConflictException duplicate(UUID fromId, UUID toId, String mode) {
        return new TransportConflictException(
                "An identical " + mode + " transport from " + fromId + " to " + toId + " already exists");
    }
}
