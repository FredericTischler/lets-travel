package com.travelplan.identity;

import com.travelplan.identity.entity.RefreshToken;
import com.travelplan.identity.repository.RefreshTokenRepository;
import com.travelplan.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code POST /refresh} and {@code POST /logout}
 * (V7__add_refresh_tokens.sql, {@link com.travelplan.identity.service.RefreshTokenService}):
 * rotation (single-use), the same non-disclosure treatment as {@code POST
 * /login}/{@code GET /me} for an unknown/expired/revoked token, and that
 * logout never leaks whether the token it was given actually existed.
 *
 * Uses Testcontainers (postgres:17.5-bookworm), same setup as
 * {@link AuthIntegrationTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class RefreshTokenIntegrationTest {

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
        registry.add("JWT_SIGNING_KEY", () -> "test-only-signing-key-must-be-at-least-32-bytes-long");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:1");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void loginReturnsARefreshTokenAlongsideTheAccessToken() {
        Map<String, Object> body = login("refresh-login@example.com", "secret123");

        assertThat((String) body.get("token")).isNotBlank();
        assertThat((String) body.get("refreshToken")).isNotBlank();
        assertThat(body).doesNotContainEntry("refreshToken", body.get("token"));
    }

    @Test
    void refreshExchangesAValidTokenForAFreshPairAndRotatesIt() {
        Map<String, Object> loginBody = login("refresh-ok@example.com", "secret123");
        String originalRefreshToken = (String) loginBody.get("refreshToken");

        ResponseEntity<Map> refreshResponse = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", originalRefreshToken), Map.class);

        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) refreshResponse.getBody().get("token")).isNotBlank();
        String newRefreshToken = (String) refreshResponse.getBody().get("refreshToken");
        assertThat(newRefreshToken).isNotBlank().isNotEqualTo(originalRefreshToken);

        // Rotation: the original token is now redeemed — replaying it is a 401, not a second 200.
        ResponseEntity<Map> replay = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", originalRefreshToken), Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The freshly rotated token works.
        ResponseEntity<Map> secondRefresh = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", newRefreshToken), Map.class);
        assertThat(secondRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnknownRefreshTokenIsRejectedWithTheSameGenericMessageAsAnInvalidAccessToken() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", "this-was-never-issued"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Invalid or missing token");
    }

    @Test
    void aBlankRefreshTokenIsRejectedAsAValidationErrorBeforeAnyLookup() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", ""), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anExpiredRefreshTokenIsRejected() {
        // The API cannot produce an already-expired token (7-day validity) —
        // seeded directly via the repository, hashing the raw value the exact
        // same way RefreshTokenService does (SHA-256 hex), the only way to
        // simulate this state, same idiom as travel-service's legacy-data tests.
        Map<String, Object> loginBody = login("refresh-expired@example.com", "secret123");
        UUID userId = UUID.fromString((String) loginBody.get("id"));
        String rawToken = "test-raw-refresh-token-value";
        RefreshToken expired = new RefreshToken(userId, sha256Hex(rawToken),
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        refreshTokenRepository.save(expired);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", rawToken), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesTheTokenAndIsIdempotentlyQuietAboutUnknownOnes() {
        Map<String, Object> loginBody = login("refresh-logout@example.com", "secret123");
        String refreshToken = (String) loginBody.get("refreshToken");

        ResponseEntity<Void> logoutResponse = restTemplate.postForEntity(
                "/logout", Map.of("refreshToken", refreshToken), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The logged-out token no longer works.
        ResponseEntity<Map> afterLogout = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", refreshToken), Map.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Logging out an unknown token is still 204 — never a way to probe existence.
        ResponseEntity<Void> unknownLogout = restTemplate.postForEntity(
                "/logout", Map.of("refreshToken", "never-issued"), Void.class);
        assertThat(unknownLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void refreshFailsOnceTheUserIsSoftDeleted() {
        Map<String, Object> loginBody = login("refresh-deleted-user@example.com", "secret123");
        String refreshToken = (String) loginBody.get("refreshToken");
        UUID userId = UUID.fromString((String) loginBody.get("id"));

        userRepository.findById(userId).ifPresent(user -> {
            user.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            userRepository.save(user);
        });

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", refreshToken), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Regression test for a bug caught by security review before this ever
     * merged: {@code redeem()} used to revoke the token BEFORE checking the
     * user was active, inside the same {@code @Transactional} method chain as
     * {@code AuthService.refresh()} — throwing {@code InvalidTokenException}
     * (unchecked) after that write marked the whole transaction
     * rollback-only, silently discarding the revoke on every failure path.
     * The fix reorders every check before the write, so revocation only ever
     * happens on the path that actually returns a user. This test locks in
     * the resulting (accepted, documented) semantics: a token that failed
     * once only because its user was inactive is NOT permanently revoked —
     * it works again once the user is (as here, artificially) reactivated.
     */
    @Test
    void aTokenRejectedOnlyForAnInactiveUserStillWorksOnceTheUserIsReactivated() {
        Map<String, Object> loginBody = login("refresh-reactivated@example.com", "secret123");
        String refreshToken = (String) loginBody.get("refreshToken");
        UUID userId = UUID.fromString((String) loginBody.get("id"));

        userRepository.findById(userId).ifPresent(user -> {
            user.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            userRepository.save(user);
        });
        ResponseEntity<Map> whileInactive = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", refreshToken), Map.class);
        assertThat(whileInactive.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        userRepository.findById(userId).ifPresent(user -> {
            user.setDeletedAt(null);
            userRepository.save(user);
        });
        ResponseEntity<Map> afterReactivation = restTemplate.postForEntity(
                "/refresh", Map.of("refreshToken", refreshToken), Map.class);
        assertThat(afterReactivation.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> login(String email, String password) {
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", password), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                "/login", Map.of("email", email, "password", password), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = loginResponse.getBody();
        return body;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
