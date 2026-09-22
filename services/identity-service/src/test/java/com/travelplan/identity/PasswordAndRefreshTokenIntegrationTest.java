package com.travelplan.identity;

import com.travelplan.identity.entity.RefreshToken;
import com.travelplan.identity.repository.RefreshTokenRepository;
import com.travelplan.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the additions covered by
 * docs/lets-travel-architecture-decisions.md addenda "Mot de passe" (G5),
 * "Normalisation des emails" (G12) and "Refresh token" (G10):
 *
 * <ul>
 *   <li>Email normalisation: a different-case duplicate is rejected at
 *       creation, and login is case-insensitive.</li>
 *   <li>{@code PATCH /users/{id}/password}: owner success/failure paths,
 *       admin-on-another-account success without a current password,
 *       non-owner/non-admin 403, too-short new password 400.</li>
 *   <li>{@code POST /auth/refresh} / {@code POST /auth/logout}: login
 *       returns a refresh token, rotation issues a fresh pair and consumes
 *       the old one, an unknown/expired/revoked token is a generic 401.</li>
 * </ul>
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PasswordAndRefreshTokenIntegrationTest {

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

    private static final String PASSWORD = "secret123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private TestAccounts accounts;

    @BeforeEach
    void setUpAccounts() {
        accounts = new TestAccounts(restTemplate, userRepository, passwordEncoder);
    }

    // ---------------------------------------------------------------
    // Email normalisation (G12)
    // ---------------------------------------------------------------

    @Test
    void creatingAUserWithADifferentCaseEmailIsRejectedAsADuplicate() {
        String email = uniqueEmail("norm-dup");
        assertThat(createUser(email).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> conflict = createUser("  " + email.toUpperCase() + "  ");

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginIsCaseAndWhitespaceInsensitiveOnTheEmail() {
        String email = uniqueEmail("norm-login");
        createUser(email);

        ResponseEntity<Map> response = login("  " + email.toUpperCase() + "  ", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("email", email);
    }

    // ---------------------------------------------------------------
    // PATCH /users/{id}/password (G5)
    // ---------------------------------------------------------------

    @Test
    void ownerCanChangeTheirOwnPasswordWithTheCorrectCurrentPassword() {
        String email = uniqueEmail("pwd-owner");
        String id = createUser(email).getBody().get("id").toString();
        String token = accounts.login(email, PASSWORD);

        ResponseEntity<Map> response = changePassword(
                id, token, Map.of("currentPassword", PASSWORD, "newPassword", "new-secret-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("email", email);
        // The old password no longer works, the new one does.
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, "new-secret-1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ownerChangingPasswordWithTheWrongCurrentPasswordGets401() {
        String email = uniqueEmail("pwd-wrong-current");
        String id = createUser(email).getBody().get("id").toString();
        String token = accounts.login(email, PASSWORD);

        ResponseEntity<Map> response = changePassword(
                id, token, Map.of("currentPassword", "not-the-right-one", "newPassword", "new-secret-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Invalid credentials");
        // The password must not have changed.
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aNonOwnerNonAdminCallerGets403() {
        String targetEmail = uniqueEmail("pwd-target");
        String targetId = createUser(targetEmail).getBody().get("id").toString();

        String attackerEmail = uniqueEmail("pwd-attacker");
        createUser(attackerEmail);
        String attackerToken = accounts.login(attackerEmail, PASSWORD);

        ResponseEntity<Map> response = changePassword(
                targetId, attackerToken, Map.of("currentPassword", PASSWORD, "newPassword", "new-secret-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // The target's password must not have changed.
        assertThat(login(targetEmail, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAdminCanChangeAnotherUsersPasswordWithoutACurrentPassword() {
        String email = uniqueEmail("pwd-admin-target");
        String id = createUser(email).getBody().get("id").toString();
        String adminToken = accounts.newAdminToken();

        ResponseEntity<Map> response = changePassword(
                id, adminToken, Map.of("newPassword", "new-secret-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, "new-secret-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTooShortNewPasswordIsRejectedWith400() {
        String email = uniqueEmail("pwd-too-short");
        String id = createUser(email).getBody().get("id").toString();
        String token = accounts.login(email, PASSWORD);

        ResponseEntity<Map> response = changePassword(
                id, token, Map.of("currentPassword", PASSWORD, "newPassword", "short"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The password must not have changed.
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void changePasswordOnAnUnknownIdReturns404ForAnAdminCaller() {
        String adminToken = accounts.newAdminToken();

        ResponseEntity<Map> response = changePassword(
                UUID.randomUUID().toString(), adminToken, Map.of("newPassword", "new-secret-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------
    // POST /auth/refresh, POST /auth/logout (G10)
    // ---------------------------------------------------------------

    @Test
    void loginReturnsANonBlankRefreshToken() {
        String email = uniqueEmail("refresh-login");
        createUser(email);

        ResponseEntity<Map> response = login(email, PASSWORD);

        assertThat(response.getBody()).containsKey("refreshToken");
        assertThat((String) response.getBody().get("refreshToken")).isNotBlank();
    }

    @Test
    void refreshRotatesTheTokenPairAndTheOldRefreshTokenBecomesInvalid() {
        String email = uniqueEmail("refresh-rotate");
        createUser(email);
        String oldRefreshToken = (String) login(email, PASSWORD).getBody().get("refreshToken");

        ResponseEntity<Map> refreshed = restTemplate.postForEntity(
                "/auth/refresh", Map.of("refreshToken", oldRefreshToken), Map.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).containsKey("accessToken");
        assertThat(refreshed.getBody()).containsKey("refreshToken");
        String newAccessToken = (String) refreshed.getBody().get("accessToken");
        String newRefreshToken = (String) refreshed.getBody().get("refreshToken");
        assertThat(newAccessToken).isNotBlank();
        assertThat(newRefreshToken).isNotBlank().isNotEqualTo(oldRefreshToken);

        // The new access token works against a protected endpoint.
        HttpEntity<Void> newAuth = new HttpEntity<>(TestAccounts.bearer(newAccessToken));
        ResponseEntity<Map> me = restTemplate.exchange("/me", HttpMethod.GET, newAuth, Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("email", email);

        // Replaying the OLD refresh token now fails: it was consumed by rotation.
        ResponseEntity<Map> replay = restTemplate.postForEntity(
                "/auth/refresh", Map.of("refreshToken", oldRefreshToken), Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody()).containsEntry("error", "Invalid or missing token");
    }

    @Test
    void refreshWithAnUnknownTokenReturns401() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/auth/refresh", Map.of("refreshToken", "not-a-real-token"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Invalid or missing token");
    }

    @Test
    void refreshWithAnExpiredTokenReturns401() throws Exception {
        String email = uniqueEmail("refresh-expired");
        UUID userId = UUID.fromString((String) createUser(email).getBody().get("id"));

        // Insert an already-expired row directly (bypassing RefreshTokenService,
        // which always issues a token 7 days in the future) — same SHA-256 hash
        // scheme RefreshTokenService applies to the plaintext it hands out.
        String plaintext = "expired-test-token-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        refreshTokenRepository.save(new RefreshToken(userId, sha256Hex(plaintext), now.minusDays(8), now.minusDays(1)));

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/auth/refresh", Map.of("refreshToken", plaintext), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutThenRefreshWithTheSameTokenReturns401() {
        String email = uniqueEmail("refresh-logout");
        createUser(email);
        String refreshToken = (String) login(email, PASSWORD).getBody().get("refreshToken");

        ResponseEntity<Void> logoutResponse = restTemplate.postForEntity(
                "/auth/logout", Map.of("refreshToken", refreshToken), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> refreshAfterLogout = restTemplate.postForEntity(
                "/auth/refresh", Map.of("refreshToken", refreshToken), Map.class);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutIsAlways204EvenForAnUnknownToken() {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/auth/logout", Map.of("refreshToken", "never-issued"), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private ResponseEntity<Map> createUser(String email) {
        return restTemplate.postForEntity("/users", Map.of("email", email, "password", PASSWORD), Map.class);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return restTemplate.postForEntity("/login", Map.of("email", email, "password", password), Map.class);
    }

    private ResponseEntity<Map> changePassword(String id, String token, Map<String, String> body) {
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, TestAccounts.bearer(token));
        return restTemplate.exchange("/users/" + id + "/password", HttpMethod.PATCH, entity, Map.class);
    }

    /** Same SHA-256-hex scheme {@code RefreshTokenService} applies to a plaintext refresh token. */
    private static String sha256Hex(String value) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
