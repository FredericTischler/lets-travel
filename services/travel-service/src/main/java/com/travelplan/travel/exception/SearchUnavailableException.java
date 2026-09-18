package com.travelplan.travel.exception;

/**
 * Thrown when a search/autocomplete request cannot be served because
 * Elasticsearch is unreachable or returned an error.
 *
 * Deliberately unchecked and narrow: unlike the primary Neo4j write path
 * (which is the source of truth and must never silently fail), a read
 * against the secondary search index can fail loudly and immediately — see
 * docs/lets-travel-architecture-decisions.md §6. Mapped to
 * {@code 503 Service Unavailable} by {@link GlobalExceptionHandler}.
 */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String operation, Throwable cause) {
        super("Elasticsearch " + operation + " is currently unavailable", cause);
    }
}
