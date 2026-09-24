package com.travelplan.identity.dto;

/**
 * API response for a successful {@code POST /refresh}: a fresh access token
 * plus a fresh refresh token (rotation — the one just redeemed no longer
 * works, see {@code RefreshTokenService}).
 */
public class RefreshResponse {

    private final String token;
    private final String refreshToken;

    public RefreshResponse(String token, String refreshToken) {
        this.token = token;
        this.refreshToken = refreshToken;
    }

    public String getToken() {
        return token;
    }

    public String getRefreshToken() {
        return refreshToken;
    }
}
