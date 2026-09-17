package com.travelplan.identity.exception;

import java.util.UUID;

/**
 * Thrown when a report is not found (absent or soft-deleted).
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class ReportNotFoundException extends RuntimeException {

    public ReportNotFoundException(UUID id) {
        super("Report not found: " + id);
    }
}
