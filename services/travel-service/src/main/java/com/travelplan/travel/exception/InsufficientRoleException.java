package com.travelplan.travel.exception;

/**
 * Thrown by {@link com.travelplan.travel.service.TokenValidationService}
 * whenever the caller presents an otherwise-valid token that does not carry
 * the {@code ADMIN} role claim.
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
