package com.travelplan.identity.exception;

/**
 * Thrown by {@link com.travelplan.identity.service.AuthService#requireAdmin}
 * whenever the caller presents an otherwise-valid, currently-active token
 * that does not carry the {@code ADMIN} role claim (see
 * {@link com.travelplan.identity.service.JwtService#CLAIM_ROLE}).
 *
 * Distinct from {@link InvalidTokenException} on purpose: the caller IS
 * authenticated (401 would be wrong), they are simply not authorized to
 * perform this action (403) — the explicit least-privilege enforcement
 * required by docs/sujet.md §4, on top of mere token validity.
 */
public class InsufficientRoleException extends RuntimeException {

    public InsufficientRoleException() {
        super("Administrator role required");
    }
}
