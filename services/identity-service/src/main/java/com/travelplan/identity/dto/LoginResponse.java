package com.travelplan.identity.dto;

import java.util.UUID;

/**
 * API response for a successful {@code POST /login}.
 *
 * Includes a short-lived (15 min) JWT usable as a Bearer token against
 * {@code GET /me}, plus a longer-lived opaque {@code refreshToken}
 * (partial mitigation of security audit G10 — see
 * {@link com.travelplan.identity.service.RefreshTokenService}) that can be
 * exchanged for a fresh pair via {@code POST /auth/refresh} without
 * re-entering a password. {@code refreshToken} is additive: {@code id},
 * {@code email} and {@code token} keep their original meaning. Never
 * includes the password hash.
 */
public class LoginResponse {

    private final UUID id;
    private final String email;
    private final String token;
    private final String refreshToken;

    public LoginResponse(UUID id, String email, String token, String refreshToken) {
        this.id = id;
        this.email = email;
        this.token = token;
        this.refreshToken = refreshToken;
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

    public String getRefreshToken() {
        return refreshToken;
    }
}