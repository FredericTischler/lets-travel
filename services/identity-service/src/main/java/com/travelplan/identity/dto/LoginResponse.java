package com.travelplan.identity.dto;

import java.util.UUID;

/**
 * API response for a successful {@code POST /login}.
 *
 * {@code token} is a short-lived (15 min) JWT usable as a Bearer token
 * against every protected endpoint. {@code refreshToken} is a separate,
 * longer-lived opaque credential (see {@code RefreshTokenService}) usable
 * exactly once against {@code POST /refresh} to obtain a new pair without
 * asking for credentials again. Never includes the password hash.
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
