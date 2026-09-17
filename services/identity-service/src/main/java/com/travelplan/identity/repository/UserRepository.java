package com.travelplan.identity.repository;

import com.travelplan.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link User}.
 *
 * All query methods filter on {@code deleted_at IS NULL} to honour the
 * soft-delete contract. No business logic lives here — only data access.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find a non-deleted user by id.
     */
    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deletedAt IS NULL")
    Optional<User> findActiveById(@Param("id") UUID id);

    /**
     * Return all non-deleted users.
     */
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL")
    List<User> findAllActive();

    /**
     * Check for an active user with the given email (for 409 conflict detection).
     */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
}