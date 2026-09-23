package com.travelplan.identity.service;

import com.travelplan.identity.dto.CreateUserRequest;
import com.travelplan.identity.dto.UpdateEmailRequest;
import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.exception.EmailAlreadyActiveException;
import com.travelplan.identity.exception.InsufficientRoleException;
import com.travelplan.identity.exception.UserNotFoundException;
import com.travelplan.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 * - Least privilege at account creation (docs/lets-travel-architecture-decisions.md
 *   §1 addendum "Bootstrap et création d'ADMIN"): only an authenticated ADMIN
 *   may create an ADMIN account, and an omitted role means TRAVELER.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

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
     * <p>Role rules: an omitted role defaults to {@code TRAVELER} (least
     * privilege — never a silent ADMIN); asking for {@code ADMIN} requires
     * {@code callerIsAdmin}, established by the controller from the caller's
     * own Bearer token ({@link AuthService#isAdmin}), never from the body.</p>
     *
     * @param callerIsAdmin whether the request carries a valid ADMIN token
     * @throws InsufficientRoleException if an ADMIN account is requested by a non-admin caller
     * @throws EmailAlreadyActiveException if an active user already owns {@code email}
     */
    @Transactional
    public UserResponse create(CreateUserRequest request, boolean callerIsAdmin) {
        String role = (request.getRole() != null) ? request.getRole() : JwtService.ROLE_TRAVELER;
        if (JwtService.ROLE_ADMIN.equals(role) && !callerIsAdmin) {
            // No email in the log line: it is the attacker's input and PII.
            log.warn("Rejected creation of an ADMIN account by a non-admin caller");
            throw new InsufficientRoleException();
        }

        userRepository.findByEmailAndDeletedAtIsNull(request.getEmail())
                .ifPresent(existing -> {
                    throw new EmailAlreadyActiveException(request.getEmail());
                });

        String passwordHash = passwordEncoder.encode(request.getPassword());
        User user = new User(request.getEmail(), passwordHash, role);
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    /**
     * Startup bootstrap: creates the first ADMIN account, only if no active
     * ADMIN exists (see {@code BootstrapAdminInitializer}). Idempotent.
     *
     * @return the created admin, or empty when an active ADMIN already exists
     * @throws IllegalStateException if no ADMIN exists but {@code email} is
     *         already taken by an active non-admin account — there is no
     *         role-promotion path, so this is a misconfiguration to surface
     */
    @Transactional
    public Optional<UserResponse> bootstrapAdminIfAbsent(String email, String password) {
        if (userRepository.existsByRoleAndDeletedAtIsNull(JwtService.ROLE_ADMIN)) {
            return Optional.empty();
        }
        if (userRepository.findByEmailAndDeletedAtIsNull(email).isPresent()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL is already used by an active "
                    + "non-admin account and no active ADMIN exists: choose another bootstrap email");
        }
        User admin = new User(email, passwordEncoder.encode(password), JwtService.ROLE_ADMIN);
        return Optional.of(UserResponse.from(userRepository.save(admin)));
    }

    /** Whether at least one active ADMIN account exists. */
    public boolean hasActiveAdmin() {
        return userRepository.existsByRoleAndDeletedAtIsNull(JwtService.ROLE_ADMIN);
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
                .toList();
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
        user.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
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