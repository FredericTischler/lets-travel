package com.travelplan.travel.exception;

/**
 * Thrown by {@link com.travelplan.travel.service.TokenValidationService#requireValidToken}
 * whenever a protected endpoint cannot validate the {@code Authorization}
 * header — missing header, malformed header, expired token, or invalid
 * signature. The message is intentionally generic and identical in every
 * case (see {@link GlobalExceptionHandler}), same non-disclosure philosophy
 * as identity-service's own {@code InvalidTokenException}.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("Invalid or missing token");
    }
}