package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when both endpoints of a route request exist and are active, but no
 * chain of active {@code TRANSPORT} edges connects them within the bounded
 * number of hops this project searches (see {@code TransportRepository}).
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler} — distinct from
 * {@link DestinationNotFoundException}, which covers an absent/soft-deleted
 * endpoint rather than an absent path between two real ones.
 */
public class RouteNotFoundException extends RuntimeException {

    public RouteNotFoundException(UUID fromId, UUID toId) {
        super("No route found from " + fromId + " to " + toId);
    }
}
