package com.travelplan.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /login}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}
 * and returned as HTTP 400.
 */
public class LoginRequest {

    @NotBlank(message = "must not be blank")
    @Email(message = "must be a valid email address")
    private String email;

    @NotBlank(message = "must not be blank")
    private String password;

    public LoginRequest() {
        // required for Jackson deserialization
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}