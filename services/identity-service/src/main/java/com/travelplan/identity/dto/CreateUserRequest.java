package com.travelplan.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /users}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}
 * and returned as HTTP 400. The plaintext password never leaves this DTO —
 * it is hashed by {@link com.travelplan.identity.service.UserService} before
 * persistence and is never included in any response.
 *
 * {@code role} is optional: {@code @Pattern} does not validate a {@code null}
 * value (Bean Validation semantics), so an omitted role is left to
 * {@link com.travelplan.identity.service.UserService#create} to default to
 * {@code TRAVELER} (least privilege). A role that IS supplied must be one of
 * the three known values, or this fails validation with a 400, same as any
 * other constraint; whether the caller is ALLOWED to ask for it (ADMIN needs
 * an admin token) is an authorization decision made in the service, not here.
 */
public class CreateUserRequest {

    @NotBlank(message = "must not be blank")
    @Email(message = "must be a valid email address")
    private String email;

    @NotBlank(message = "must not be blank")
    @Size(min = 8, message = "must be at least 8 characters long")
    private String password;

    @Pattern(regexp = "ADMIN|TRAVEL_MANAGER|TRAVELER",
            message = "must be one of ADMIN, TRAVEL_MANAGER, TRAVELER")
    private String role;

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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}