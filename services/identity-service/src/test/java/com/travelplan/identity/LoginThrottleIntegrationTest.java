package com.travelplan.identity;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security audit G4: brute-force protection of {@code POST /login}, end to end
 * on a real PostgreSQL. Thresholds are lowered through the very env vars
 * production uses (email: 3 failures, IP: 20, window 10 min).
 *
 * <p>All requests come from the same client (127.0.0.1), so the per-IP counter
 * is shared across the tests of this class: the IP test therefore runs last
 * ({@link Order}) and every other test stays well under the IP budget.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoginThrottleIntegrationTest {

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
        registry.add("LOGIN_THROTTLE_EMAIL_MAX_FAILURES", () -> "3");
        registry.add("LOGIN_THROTTLE_IP_MAX_FAILURES", () -> "20");
        registry.add("LOGIN_THROTTLE_WINDOW_SECONDS", () -> "600");
    }

    private static final String PASSWORD = "secret123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @Order(1)
    void afterNFailuresTheEmailGets429WithRetryAfterEvenWithTheRightPassword() {
        String email = uniqueEmail("locked");
        createUser(email);

        for (int i = 0; i < 3; i++) {
            assertThat(login(email, "wrong-" + i).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<Map> refused = login(email, "wrong-again");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        long retryAfter = Long.parseLong(refused.getHeaders().getFirst("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 600L);
        assertThat(refused.getBody()).containsEntry("status", 429);

        // The correct password does not open a locked account either (no oracle for the attacker).
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Case and padding do not bypass it.
        assertThat(login(email.toUpperCase(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @Order(2)
    void anotherEmailIsUnaffectedByTheLockOfTheFirst() {
        String locked = uniqueEmail("victim");
        String other = uniqueEmail("bystander");
        createUser(locked);
        createUser(other);
        for (int i = 0; i < 3; i++) {
            login(locked, "wrong-" + i);
        }
        assertThat(login(locked, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(login(other, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(3)
    void aSuccessfulLoginResetsTheEmailCounter() {
        String email = uniqueEmail("reset");
        createUser(email);

        login(email, "wrong-1");
        login(email, "wrong-2");
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);   // resets: 2 -> 0
        login(email, "wrong-3");
        login(email, "wrong-4");
        // 2 failures since the reset (threshold 3): still allowed, and it succeeds again.
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(4)
    void anUnknownEmailIsThrottledExactlyLikeAKnownOne() {
        String known = uniqueEmail("known");
        String unknown = uniqueEmail("ghost");
        createUser(known);

        ResponseEntity<Map> lastKnown = null;
        ResponseEntity<Map> lastUnknown = null;
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> k = login(known, "wrong-" + i);
            ResponseEntity<Map> u = login(unknown, "wrong-" + i);
            // Below the threshold: the two are the same 401.
            assertThat(u.getStatusCode()).isEqualTo(k.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(u.getBody()).isEqualTo(k.getBody());
        }
        lastKnown = login(known, "wrong-x");
        lastUnknown = login(unknown, "wrong-x");

        // At the threshold: the same 429, same body, same Retry-After (both ~600 s).
        assertThat(lastKnown.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lastUnknown.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lastUnknown.getBody()).isEqualTo(lastKnown.getBody());
        long knownWait = Long.parseLong(lastKnown.getHeaders().getFirst("Retry-After"));
        long unknownWait = Long.parseLong(lastUnknown.getHeaders().getFirst("Retry-After"));
        assertThat(Math.abs(knownWait - unknownWait)).isLessThanOrEqualTo(5);
    }

    @Test
    @Order(5)
    void malformedLoginBodiesAreStill400NotCounted() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/login", Map.of("email", "not-an-email", "password", "x"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(99)
    void theClientIpIsLockedAfterTooManyFailuresAcrossDifferentEmails() {
        String innocent = uniqueEmail("innocent");
        createUser(innocent);

        // Credential stuffing: each email fails only once, so no email lock ever trips.
        HttpStatus last = HttpStatus.UNAUTHORIZED;
        int attempts = 0;
        while (last == HttpStatus.UNAUTHORIZED && attempts < 40) {
            last = HttpStatus.valueOf(login(uniqueEmail("stuffing"), "guess").getStatusCode().value());
            attempts++;
        }

        assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(attempts).isLessThanOrEqualTo(21);
        // The IP lock applies to every email, even a valid account with the right password.
        ResponseEntity<Map> blocked = login(innocent, PASSWORD);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private void createUser(String email) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", PASSWORD), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return restTemplate.postForEntity("/login", Map.of("email", email, "password", password), Map.class);
    }
}
