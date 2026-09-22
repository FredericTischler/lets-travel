package com.travelplan.identity.dto;

/**
 * API response for a successful {@code POST /auth/refresh}: a fresh
 * short-lived access token (same 15-minute JWT {@code POST /login} issues)
 * and a fresh refresh token. The refresh token that was presented is revoked
 * in the same call (see
 * {@link com.travelplan.identity.service.RefreshTokenService#rotate}) — the
 * one returned here is the only one still valid afterwards.
 */
public class RefreshResponse {

    private final String accessToken;
    private final String refreshToken;

    public RefreshResponse(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }
}
