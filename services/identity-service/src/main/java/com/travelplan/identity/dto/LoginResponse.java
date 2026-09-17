package com.travelplan.identity.dto;

import java.util.UUID;

/**
 * API response for a successful {@code POST /login}.
 *
 * Includes a short-lived (15 min) JWT usable as a Bearer token against
 * {@code GET /me}. No refresh token, no session. Never includes the
 * password hash.
 */
public class LoginResponse {

    private final UUID id;
    private final String email;
    private final String token;

    public LoginResponse(UUID id, String email, String token) {
        this.id = id;
        this.email = email;
        this.token = token;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getToken() {
        return token;
    }
}