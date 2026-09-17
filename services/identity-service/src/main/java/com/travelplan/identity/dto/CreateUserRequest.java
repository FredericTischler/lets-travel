package com.travelplan.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /users}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}
 * and returned as HTTP 400. The plaintext password never leaves this DTO —
 * it is hashed by {@link com.travelplan.identity.service.UserService} before
 * persistence and is never included in any response.
 */
public class CreateUserRequest {

    @NotBlank(message = "must not be blank")
    @Email(message = "must be a valid email address")
    private String email;

    @NotBlank(message = "must not be blank")
    @Size(min = 8, message = "must be at least 8 characters long")
    private String password;

    public CreateUserRequest() {
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