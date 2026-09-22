package com.travelplan.identity.service;

import com.travelplan.identity.dto.ChangePasswordRequest;
import com.travelplan.identity.dto.CreateUserRequest;
import com.travelplan.identity.dto.UpdateEmailRequest;
import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.exception.EmailAlreadyActiveException;
import com.travelplan.identity.exception.InsufficientRoleException;
import com.travelplan.identity.exception.InvalidCredentialsException;
import com.travelplan.identity.exception.UserNotFoundException;
import com.travelplan.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
 * - Least privilege at account creation (docs/lets-travel-architecture-decisions.md
 *   §1 addendum "Bootstrap et création d'ADMIN"): only an authenticated ADMIN
 *   may create an ADMIN account, and an omitted role means TRAVELER.
 * - Email normalisation (security audit G12): every email is normalised
 *   ({@link EmailNormalizer}, the same rule {@link LoginThrottle} applies)
 *   before it is looked up, compared for uniqueness, or persisted — so
 *   {@code A@x.com} and {@code a@x.com} are always the same account, never
 *   two.
 * - {@link #changePassword}: an account owner changing their own password
 *   must confirm the current one; an ADMIN changing someone else's does not
 *   (see method javadoc).
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

        String email = EmailNormalizer.normalize(request.getEmail());

        userRepository.findByEmailAndDeletedAtIsNull(email)
                .ifPresent(existing -> {
                    throw new EmailAlreadyActiveException(email);
                });

        String passwordHash = passwordEncoder.encode(request.getPassword());
        User user = new User(email, passwordHash, role);
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
     * {@code request.getEmail()} is normalised ({@link EmailNormalizer})
     * before the uniqueness check and before being stored.
     *
     * @throws UserNotFoundException if the user does not exist or is soft-deleted
     * @throws EmailAlreadyActiveException if another active user already owns the new email
     */
    @Transactional
    public UserResponse updateEmail(UUID id, UpdateEmailRequest request) {
        User user = userRepository.findActiveById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        String email = EmailNormalizer.normalize(request.getEmail());

        userRepository.findByEmailAndDeletedAtIsNull(email)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new EmailAlreadyActiveException(email);
                });

        user.setEmail(email);
        // the dirty check within the transaction persists the change automatically
        return UserResponse.from(user);
    }

    /**
     * Change the password of an active user.
     *
     * <p>Business rule (security audit G5): when the caller IS the account's
     * own owner ({@code callerIsOwner}, established by the controller from
     * {@link AuthService#requireOwnerOrAdmin}), {@code currentPassword} is
     * mandatory and must match the stored BCrypt hash, or the request is
     * refused with the exact same generic 401 semantics as a failed login —
     * {@link InvalidCredentialsException} does not distinguish "wrong current
     * password" from a token problem, same non-disclosure philosophy as the
     * rest of this service. When the caller is an ADMIN acting on someone
     * else's account, {@code currentPassword} is neither required nor
     * checked, even if the client supplies one: an admin does not know — and
     * is not expected to know — another user's current password.</p>
     *
     * @throws UserNotFoundException if the user does not exist or is soft-deleted
     * @throws InvalidCredentialsException if {@code callerIsOwner} and
     *         {@code request.getCurrentPassword()} is missing or does not
     *         match the stored hash
     */
    @Transactional
    public UserResponse changePassword(UUID id, ChangePasswordRequest request, boolean callerIsOwner) {
        User user = userRepository.findActiveById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        if (callerIsOwner) {
            String currentPassword = request.getCurrentPassword();
            if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new InvalidCredentialsException();
            }
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        // the dirty check within the transaction persists the change automatically
        return UserResponse.from(user);
    }
}