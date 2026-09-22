package com.travelplan.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /users/{id}/password}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}
 * and returned as HTTP 400.
 *
 * {@code currentPassword} has NO {@code @NotBlank}: whether it is required at
 * all is a business rule, not a shape rule — it only applies when the caller
 * is the account's own owner (see
 * {@link com.travelplan.identity.service.UserService#changePassword}), never
 * when an ADMIN acts on someone else's account. {@code newPassword} carries
 * the exact same {@code @Size} constraint as {@link CreateUserRequest#getPassword()}.
 */
public class ChangePasswordRequest {

    private String currentPassword;

    @NotBlank(message = "must not be blank")
    @Size(min = 8, message = "must be at least 8 characters long")
    private String newPassword;

    public ChangePasswordRequest() {
        // required for Jackson deserialization
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
