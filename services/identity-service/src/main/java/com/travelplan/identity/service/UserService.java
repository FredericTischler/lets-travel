package com.travelplan.identity.service;

import com.travelplan.identity.dto.CreateUserRequest;
import com.travelplan.identity.dto.UpdateEmailRequest;
import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.exception.EmailAlreadyActiveException;
import com.travelplan.identity.exception.UserNotFoundException;
import com.travelplan.identity.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for user lifecycle management.
 *
 * Rules enforced here:
 * - An email address can only be registered once among active users
 *   (deleted_at IS NULL). A soft-deleted user does NOT block re-registration
 *   with the same address — the partial unique index in V1__init.sql permits it.
 * - "Delete" always means soft-delete: deleted_at is set to now(), the row stays.
 * - findById / findAll silently filter out soft-deleted rows (callers receive a
 *   404 / empty list, not a soft-delete row).
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Create a new active user. The password is hashed with BCrypt before
     * persistence; the plaintext value never reaches the repository.
     *
     * @throws EmailAlreadyActiveException if an active user already owns {@code email}
     */
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        userRepository.findByEmailAndDeletedAtIsNull(request.getEmail())
                .ifPresent(existing -> {
                    throw new EmailAlreadyActiveException(request.getEmail());
                });

        String passwordHash = passwordEncoder.encode(request.getPassword());
        User user = new User(request.getEmail(), passwordHash);
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    /**
     * Find an active user by id.
     *
     * @throws UserNotFoundException if the user does not exist or is soft-deleted
     */
    public UserResponse findById(UUID id) {
        User user = userRepository.findActiveById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return UserResponse.from(user);
    }

    /**
     * Return all active users.
     */
    public List<UserResponse> findAll() {
        return userRepository.findAllActive().stream()
                .map(UserResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Soft-delete an active user (sets deleted_at = now()).
     *
     * @throws UserNotFoundException if the user does not exist or is already soft-deleted
     */
    @Transactional
    public void delete(UUID id) {
        User user = userRepository.findActiveById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        user.setDeletedAt(OffsetDateTime.now());
        // the dirty check within the transaction persists the change automatically
    }

    /**
     * Update the email address of an active user. No other field is touched.
     *
     * @throws UserNotFoundException if the user does not exist or is soft-deleted
     * @throws EmailAlreadyActiveException if another active user already owns the new email
     */
    @Transactional
    public UserResponse updateEmail(UUID id, UpdateEmailRequest request) {
        User user = userRepository.findActiveById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        userRepository.findByEmailAndDeletedAtIsNull(request.getEmail())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new EmailAlreadyActiveException(request.getEmail());
                });

        user.setEmail(request.getEmail());
        // the dirty check within the transaction persists the change automatically
        return UserResponse.from(user);
    }
}