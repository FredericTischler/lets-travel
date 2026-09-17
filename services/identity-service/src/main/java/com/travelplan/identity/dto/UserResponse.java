package com.travelplan.identity.dto;

import com.travelplan.identity.entity.User;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response for a user resource.
 *
 * Intentionally omits {@code deleted_at}: that field is an internal
 * soft-delete implementation detail and must never be exposed over the API.
 */
public class UserResponse {

    private final UUID id;
    private final String email;
    private final OffsetDateTime createdAt;
    private final String role;

    private UserResponse(UUID id, String email, OffsetDateTime createdAt, String role) {
        this.id = id;
        this.email = email;
        this.createdAt = createdAt;
        this.role = role;
    }

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt(), user.getRole());
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getRole() {
        return role;
    }
}