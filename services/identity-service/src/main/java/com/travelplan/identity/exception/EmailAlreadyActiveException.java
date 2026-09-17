package com.travelplan.identity.exception;

/**
 * Thrown when a POST /users request arrives for an email address that is
 * already owned by an active (non-deleted) user.
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class EmailAlreadyActiveException extends RuntimeException {

    public EmailAlreadyActiveException(String email) {
        super("Email is already registered and active: " + email);
    }
}