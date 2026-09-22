package com.travelplan.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body shared by {@code POST /auth/refresh} and
 * {@code POST /auth/logout} — both take the exact same shape, a single
 * opaque refresh token value, so one DTO serves them both.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}
 * and returned as HTTP 400 — distinct from an unknown/expired/revoked token,
 * which {@link com.travelplan.identity.service.RefreshTokenService} treats as
 * a 401 (refresh) or a silent no-op (logout), never a 400.
 */
public class RefreshTokenRequest {

    @NotBlank(message = "must not be blank")
    private String refreshToken;

    public RefreshTokenRequest() {
        // required for Jackson deserialization
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
