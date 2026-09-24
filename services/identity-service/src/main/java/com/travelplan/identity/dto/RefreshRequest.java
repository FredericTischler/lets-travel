package com.travelplan.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /refresh} and {@code POST /logout}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}
 * and returned as HTTP 400 — before any lookup, same split as
 * {@code LoginRequest}.
 */
public class RefreshRequest {

    @NotBlank(message = "must not be blank")
    private String refreshToken;

    public RefreshRequest() {
        // required for Jackson deserialization
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
