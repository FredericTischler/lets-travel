package com.travelplan.travel.exception;

import java.util.UUID;

/**
 * Thrown when a {@code TRANSPORT} relationship is not found for a
 * {@code PATCH}/{@code DELETE} — either no relationship carries the given id
 * at all, or one does but does not start from the given {@code fromId} (a
 * caller must not be able to update/delete a relation via a mismatched
 * origin even if {@code transportId} exists elsewhere in the graph).
 *
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}. Unlike
 * {@link DestinationNotFoundException}, there is no soft-delete state to
 * distinguish: a {@code TRANSPORT} relationship is either physically present
 * or physically absent (docs/lets-travel-architecture-decisions.md §11
 * addendum — {@code DELETE} is a physical deletion, not a soft-delete).
 */
public class TransportNotFoundException extends RuntimeException {

    public TransportNotFoundException(UUID transportId) {
        super("Transport not found: " + transportId);
    }
}
