package com.travelplan.identity;

import com.travelplan.identity.config.BootstrapAdminInitializer;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bootstrap admin (docs/lets-travel-architecture-decisions.md §1 addendum):
 * with {@code BOOTSTRAP_ADMIN_EMAIL}/{@code BOOTSTRAP_ADMIN_PASSWORD} set, the
 * first ADMIN exists after startup, can log in and use admin routes, has a
 * BCrypt hash like any other account, and re-running the bootstrap (a restart,
 * a second replica) creates nothing more. Validation failures of the two
 * variables are covered without a Spring context by
 * {@link BootstrapAdminInitializerTest}.
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminIntegrationTest {

    private static final String BOOTSTRAP_EMAIL = "bootstrap-admin@example.com";
    private static final String BOOTSTRAP_PASSWORD = "bootstrap-Secret-9876";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.5-bookworm")
                    .withDatabaseName("identity_db")
                    .withUsername("identity_user")
                    .withPassword("test_password_only");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", () -> String.valueOf(postgres.getMappedPort(5432)));
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("JWT_SIGNING_KEY", () -> "test-only-signing-key-must-be-at-least-32-bytes-long");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:1");
        registry.add("BOOTSTRAP_ADMIN_EMAIL", () -> BOOTSTRAP_EMAIL);
        registry.add("BOOTSTRAP_ADMIN_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BootstrapAdminInitializer initializer;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Test
    void theBootstrapAdminExistsAfterStartupWithABcryptHashedPassword() {
        User admin = userRepository.findByEmailAndDeletedAtIsNull(BOOTSTRAP_EMAIL).orElseThrow();

        assertThat(admin.getRole()).isEqualTo("ADMIN");
        assertThat(admin.getPasswordHash()).startsWith("$2").isNotEqualTo(BOOTSTRAP_PASSWORD);
        assertThat(passwordEncoder.matches(BOOTSTRAP_PASSWORD, admin.getPasswordHash())).isTrue();
    }

    @Test
    void theBootstrapAdminCanLogInAndUseAdminOnlyRoutes() {
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/login", Map.of("email", BOOTSTRAP_EMAIL, "password", BOOTSTRAP_PASSWORD), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("token");

        ResponseEntity<List> users = restTemplate.exchange(
                "/users", HttpMethod.GET, new HttpEntity<>(TestAccounts.bearer(token)), List.class);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void runningTheBootstrapAgainIsIdempotent(CapturedOutput output) {
        long before = activeAdmins();

        initializer.run(null);
        initializer.run(null);

        assertThat(before).isEqualTo(1);
        assertThat(activeAdmins()).isEqualTo(1);
        assertThat(output.getAll()).contains("bootstrap admin skipped");
    }

    @Test
    void ifEveryAdminIsGoneTheNextStartupRecreatesOneAndNeverLogsTheSecrets(CapturedOutput output) {
        User admin = userRepository.findByEmailAndDeletedAtIsNull(BOOTSTRAP_EMAIL).orElseThrow();
        String oldHash = admin.getPasswordHash();
        admin.setDeletedAt(OffsetDateTime.now());
        userRepository.save(admin);
        assertThat(activeAdmins()).isZero();

        initializer.run(null);

        assertThat(activeAdmins()).isEqualTo(1);
        User recreated = userRepository.findByEmailAndDeletedAtIsNull(BOOTSTRAP_EMAIL).orElseThrow();
        assertThat(recreated.getId()).isNotEqualTo(admin.getId());
        assertThat(output.getAll())
                .contains("Bootstrap admin account created")
                .doesNotContain(BOOTSTRAP_PASSWORD)
                .doesNotContain(BOOTSTRAP_EMAIL)
                .doesNotContain(oldHash)
                .doesNotContain(recreated.getPasswordHash());
    }

    private long activeAdmins() {
        return userRepository.findAllActive().stream().filter(u -> "ADMIN".equals(u.getRole())).count();
    }
}
