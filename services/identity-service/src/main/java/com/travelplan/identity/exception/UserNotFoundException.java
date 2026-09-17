package com.travelplan.identity.exception;

import java.util.UUID;

/**
 * Thrown when a user is not found (absent or soft-deleted).
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID id) {
        super("User not found: " + id);
    }
}