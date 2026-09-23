package com.travelplan.identity.config;

import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Creates the very first ADMIN account at startup — the only way an ADMIN can
 * come into existence without already being logged in as one, since
 * {@code POST /users} refuses ADMIN creation to non-admin callers
 * (docs/lets-travel-architecture-decisions.md §1 addendum).
 *
 * <p>Driven by two OPTIONAL environment variables, injected like every other
 * secret of this repo (Vault {@code secret/identity/bootstrap-admin} -> Ansible
 * {@code app-secrets} -> {@code /opt/travel-plan/.env} -> Compose):</p>
 * <ul>
 *   <li>{@code BOOTSTRAP_ADMIN_EMAIL} and {@code BOOTSTRAP_ADMIN_PASSWORD}
 *       both unset/blank: feature off, nothing happens (a deployment that
 *       already has its admin needs no bootstrap secret).</li>
 *   <li>Exactly one of them set, an invalid email, or a password shorter than
 *       {@link #MIN_PASSWORD_LENGTH}: the service REFUSES TO START — fail-fast,
 *       never a half-configured or weak bootstrap account.</li>
 *   <li>Both set: the admin is created if, and only if, no active ADMIN exists
 *       ({@link UserService#bootstrapAdminIfAbsent}). Restarts, and the second
 *       replica of a multi-replica deploy, are no-ops. The password goes
 *       through the same BCrypt encoder as any other password and is NEVER
 *       logged (nor is the email — only the new user's id).</li>
 * </ul>
 *
 * <p>Race between replicas booting together: both may see "no admin"; the
 * partial unique index on {@code users(email)} lets exactly one insert win and
 * the loser gets a {@link DataIntegrityViolationException}, which is caught
 * here and treated as "someone else just created it" once an ADMIN is visible.</p>
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    /** Higher than the 8 of ordinary sign-up: this account can do everything. */
    static final int MIN_PASSWORD_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserService userService;
    private final String email;
    private final String password;

    public BootstrapAdminInitializer(
            UserService userService,
            @Value("${bootstrap-admin.email:}") String email,
            @Value("${bootstrap-admin.password:}") String password) {
        this.userService = userService;
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasEmail = !email.isEmpty();
        boolean hasPassword = !password.isBlank();

        if (!hasEmail && !hasPassword) {
            log.info("No bootstrap admin configured (BOOTSTRAP_ADMIN_EMAIL/BOOTSTRAP_ADMIN_PASSWORD unset)");
            return;
        }
        if (hasEmail != hasPassword) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD must be set together (or both left unset)");
        }
        if (!email.matches("[^@\\s]+@[^@\\s.]+\\.[^@\\s]+")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL is not a valid email address");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }

        Optional<UserResponse> created;
        try {
            created = userService.bootstrapAdminIfAbsent(email, password);
        } catch (DataIntegrityViolationException race) {
            if (!userService.hasActiveAdmin()) {
                throw race;
            }
            created = Optional.empty();
        }

        if (created.isPresent()) {
            log.warn("Bootstrap admin account created (id={})",
                    created.get().getId());
        } else {
            log.info("An active ADMIN already exists: bootstrap admin skipped");
        }
    }
}
