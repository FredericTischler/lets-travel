package com.travelplan.identity.exception;

/**
 * Thrown by {@link com.travelplan.identity.service.AuthService#getCurrentUser}
 * whenever {@code GET /me} cannot resolve a valid, currently-active user from
 * the {@code Authorization} header — missing header, malformed header,
 * expired token, invalid signature, or a token whose subject no longer maps
 * to an active user. The message is intentionally generic and identical in
 * every case (see {@link GlobalExceptionHandler}), consistent with the
 * non-disclosure philosophy already applied to {@code POST /login}.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("Invalid or missing token");
    }
}