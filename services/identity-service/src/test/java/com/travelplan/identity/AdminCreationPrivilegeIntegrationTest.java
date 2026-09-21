package com.travelplan.identity;

import com.travelplan.identity.entity.User;
import com.travelplan.identity.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hole this class guards: {@code POST /users} is public (people must be
 * able to sign up) and used to accept {@code role=ADMIN}, so anyone could mint
 * an administrator. Rules now (docs/lets-travel-architecture-decisions.md §1
 * addendum "Bootstrap et création d'ADMIN"):
 *
 * <ul>
 *   <li>no/invalid/non-admin token: TRAVELER (default) or TRAVEL_MANAGER only;
 *       asking for ADMIN is a 403 and creates nothing;</li>
 *   <li>a valid ADMIN token may create any role, ADMIN included;</li>
 *   <li>an omitted role is TRAVELER, never a silent ADMIN.</li>
 * </ul>
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminCreationPrivilegeIntegrationTest {

    private static final String SIGNING_KEY = "test-only-signing-key-must-be-at-least-32-bytes-long";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.5-bookworm")
                    .withDatabaseName("identity_db")
                    .withUsername("identity_user")
                    .withPassword("test_password_only");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", () -> String.valueOf(postgres.getMappedPort(5432)));
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("JWT_SIGNING_KEY", () -> SIGNING_KEY);
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:1");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestAccounts accounts;

    @BeforeEach
    void setUpAccounts() {
        accounts = new TestAccounts(restTemplate, userRepository, passwordEncoder);
    }

    @Test
    void publicCallerAskingForAdminGets403AndNothingIsCreated() {
        String email = "wannabe-admin@example.com";

        ResponseEntity<Map> response = post(email, "ADMIN", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Administrator role required");
        assertThat(userRepository.findByEmailAndDeletedAtIsNull(email)).isEmpty();
    }

    @Test
    void anInvalidOrForgedTokenIsTreatedAsAnonymousNotAsAnAdmin() {
        // Garbage token: not a 401 on this public route, but no privilege either.
        assertThat(post("garbage-token@example.com", "ADMIN", "Bearer not-a-real-jwt").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Signed with ANOTHER key while claiming role=ADMIN and a real admin's id.
        UUID adminId = accounts.createAdmin("real-admin-for-forgery@example.com");
        String forged = Jwts.builder()
                .subject(adminId.toString())
                .claim("role", "ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(
                        "another-key-that-is-also-32-bytes-long-xx".getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThat(post("forged-key@example.com", "ADMIN", "Bearer " + forged).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Correct key, correct admin id, but EXPIRED.
        String expired = Jwts.builder()
                .subject(adminId.toString())
                .claim("role", "ADMIN")
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThat(post("expired-token@example.com", "ADMIN", "Bearer " + expired).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Non-ADMIN callers still sign up normally with an invalid token: it is
        // ignored, not punished.
        assertThat(post("ignored-token@example.com", "TRAVELER", "Bearer not-a-real-jwt").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aTravelerOrTravelManagerTokenDoesNotUnlockAdminCreation() {
        for (String role : List.of("TRAVELER", "TRAVEL_MANAGER")) {
            String email = "lower-" + role.toLowerCase() + "@example.com";
            assertThat(post(email, role, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String token = accounts.login(email, "secret123");

            ResponseEntity<Map> response = post("escalation-by-" + role.toLowerCase() + "@example.com",
                    "ADMIN", "Bearer " + token);

            assertThat(response.getStatusCode()).as("ADMIN requested with a %s token", role)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void aSoftDeletedAdminsStillUnexpiredTokenNoLongerCreatesAdmins() {
        String email = "fired-admin@example.com";
        UUID id = accounts.createAdmin(email);
        String token = accounts.login(email, TestAccounts.DEFAULT_PASSWORD);
        User user = userRepository.findActiveById(id).orElseThrow();
        user.setDeletedAt(OffsetDateTime.now());
        userRepository.save(user);

        ResponseEntity<Map> response = post("by-fired-admin@example.com", "ADMIN", "Bearer " + token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAdminTokenCanCreateAnAdminAndTheNewAdminCanUseAdminRoutes() {
        String adminToken = accounts.newAdminToken();
        String email = "second-admin@example.com";

        ResponseEntity<Map> response = post(email, "ADMIN", "Bearer " + adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("role", "ADMIN");

        String newAdminToken = accounts.login(email, "secret123");
        ResponseEntity<List> users = restTemplate.exchange(
                "/users", HttpMethod.GET, new HttpEntity<>(TestAccounts.bearer(newAdminToken)), List.class);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAdminTokenCanCreateEveryOtherRoleAndOmittingTheRoleStillMeansTraveler() {
        String adminToken = accounts.newAdminToken();

        assertThat(post("by-admin-manager@example.com", "TRAVEL_MANAGER", "Bearer " + adminToken).getBody())
                .containsEntry("role", "TRAVEL_MANAGER");
        assertThat(post("by-admin-traveler@example.com", "TRAVELER", "Bearer " + adminToken).getBody())
                .containsEntry("role", "TRAVELER");
        // Even an admin gets the least-privilege default when it says nothing.
        assertThat(post("by-admin-default@example.com", null, "Bearer " + adminToken).getBody())
                .containsEntry("role", "TRAVELER");
    }

    @Test
    void publicSignUpStillWorksForTravelerAndTravelManagerAndDefaultsToTraveler() {
        assertThat(post("public-default@example.com", null, null).getBody()).containsEntry("role", "TRAVELER");
        assertThat(post("public-manager@example.com", "TRAVEL_MANAGER", null).getBody())
                .containsEntry("role", "TRAVEL_MANAGER");
    }

    @Test
    void theDatabaseNoLongerDefaultsARoleLessInsertToAdmin() {
        // V6__drop_role_default.sql: an INSERT that forgets the role must FAIL
        // (NOT NULL), not silently produce the most privileged account.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO users (email, password_hash) VALUES (?, ?)", "no-role-row@example.com", "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ResponseEntity<Map> post(String email, String role, String authorization) {
        Map<String, String> body = new java.util.HashMap<>();
        body.put("email", email);
        body.put("password", "secret123");
        if (role != null) {
            body.put("role", role);
        }
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set("Authorization", authorization);
        }
        return restTemplate.exchange("/users", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
