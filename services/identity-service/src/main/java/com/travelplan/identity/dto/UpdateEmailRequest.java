package com.travelplan.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PATCH /users/{id}}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}
 * and returned as HTTP 400. This DTO only ever updates the email address —
 * the password is out of scope for this endpoint.
 */
public class UpdateEmailRequest {

    @NotBlank(message = "must not be blank")
    @Email(message = "must be a valid email address")
    private String email;

    public UpdateEmailRequest() {
        // required for Jackson deserialization
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}