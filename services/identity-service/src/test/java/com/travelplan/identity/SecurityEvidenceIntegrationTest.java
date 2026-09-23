package com.travelplan.identity;

import com.travelplan.identity.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable evidence for docs/security-audit.md (audit grid: "protected
 * against SQL injection", "passwords are encrypted", "credentials handled
 * securely", "XSS"). Each test is named after the claim it proves; the doc
 * points here.
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class SecurityEvidenceIntegrationTest {

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

    // ---------------------------------------------------------------- SQL injection

    @Test
    void sqlInjectionPayloadsInLoginAreRejectedWithoutTouchingTheUsersTable() {
        register("victim-sqli@example.com", "TRAVELER", "secret123");
        long usersBefore = countUsers();

        // Not even an email: stopped by Bean Validation (400), never reaches SQL.
        assertThat(login("' OR '1'='1", "whatever").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Syntactically valid emails that CARRY SQL syntax: reach the repository as a
        // bound parameter (JPA derived query) and match nothing -> the same plain 401
        // as any unknown email, never a 500, never a login.
        ResponseEntity<Map> unknown = login("nobody@example.com", "whatever");
        for (String payload : List.of("a'OR'1'='1'--@example.com", "x'||'y@example.com", "'or''='@example.com")) {
            ResponseEntity<Map> response = login(payload, "whatever");
            assertThat(response.getStatusCode()).as("email %s", payload).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).as("email %s", payload).isEqualTo(unknown.getBody());
        }

        // The classic always-true trick in the PASSWORD field of a real account: 401.
        assertThat(login("victim-sqli@example.com", "' OR '1'='1").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(countUsers()).isEqualTo(usersBefore);
    }

    @Test
    void aSqlPayloadStoredAsAnEmailIsStoredAndReadBackAsPlainData() {
        String hostile = "x'||'y@example.com";
        long usersBefore = countUsers();

        ResponseEntity<Map> created = register(hostile, "TRAVELER", "secret123");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("email", hostile);
        assertThat(countUsers()).isEqualTo(usersBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Long.class, hostile)).isEqualTo(1L);
    }

    @Test
    void sqlAndScriptPayloadsInAReportReasonAreStoredVerbatimAndTheTableSurvives() {
        String reporterEmail = "sqli-reporter@example.com";
        register(reporterEmail, "TRAVELER", "secret123");
        String reporterToken = accounts.login(reporterEmail, "secret123");
        String reportedId = (String) register("sqli-reported@example.com", "TRAVELER", "secret123")
                .getBody().get("id");
        String hostile = "'); DROP TABLE reports; -- <script>alert(1)</script>";

        ResponseEntity<Map> created = restTemplate.exchange("/reports", HttpMethod.POST,
                new HttpEntity<>(Map.of("reportedUserId", reportedId, "reason", hostile),
                        TestAccounts.bearer(reporterToken)), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // XSS position (ADR §5ter.3): stored and returned as inert text in a JSON
        // response; escaping is the job of the renderer (Angular).
        assertThat(created.getBody()).containsEntry("reason", hostile);
        assertThat(created.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reports", Long.class)).isPositive();
    }

    // -------------------------------------------------------------- password hashing

    @Test
    void passwordsAreStoredAsSaltedBcryptHashesNeverAsPlaintext() {
        String password = "correct horse battery staple";
        register("hash-a@example.com", "TRAVELER", password);
        register("hash-b@example.com", "TRAVELER", password);

        String hashA = storedHash("hash-a@example.com");
        String hashB = storedHash("hash-b@example.com");

        assertThat(hashA)
                .matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$")
                .doesNotContain(password)
                .as("same password, different salt").isNotEqualTo(hashB);
        assertThat(passwordEncoder.matches(password, hashA)).isTrue();
        // No column of the users table other than password_hash holds the plaintext.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ? OR role = ?", Long.class, password, password))
                .isZero();
    }

    @Test
    void neitherThePlaintextPasswordNorItsHashAppearsInAnyResponse() {
        String password = "Sup3r-secret-pw-marker";
        String email = "no-leak-marker@example.com";
        String adminToken = accounts.newAdminToken();

        ResponseEntity<String> created = restTemplate.exchange("/users", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", password)), String.class);
        String id = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.id");
        String hash = storedHash(email);

        ResponseEntity<String> loginResponse = restTemplate.exchange("/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", password)), String.class);
        String token = com.jayway.jsonpath.JsonPath.read(loginResponse.getBody(), "$.token");
        HttpEntity<Void> admin = new HttpEntity<>(TestAccounts.bearer(adminToken));

        List<ResponseEntity<String>> responses = List.of(
                created,
                loginResponse,
                restTemplate.exchange("/me", HttpMethod.GET, new HttpEntity<>(TestAccounts.bearer(token)), String.class),
                restTemplate.exchange("/users", HttpMethod.GET, admin, String.class),
                restTemplate.exchange("/users/" + id, HttpMethod.GET, admin, String.class),
                restTemplate.exchange("/login", HttpMethod.POST,
                        new HttpEntity<>(Map.of("email", email, "password", "wrong-password")), String.class),
                restTemplate.exchange("/users", HttpMethod.POST,
                        new HttpEntity<>(Map.of("email", email, "password", password)), String.class));

        for (ResponseEntity<String> response : responses) {
            String body = String.valueOf(response.getBody());
            assertThat(body).doesNotContain(password).doesNotContain(hash).doesNotContain("password_hash")
                    .doesNotContain("passwordHash");
        }
        // The JWT payload holds only sub/email/role/iat/exp, no secret material.
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain(password).doesNotContain(hash);
    }

    // ---------------------------------------------------------------- JWT robustness

    @Test
    void forgedExpiredAndTamperedTokensAreAllRejectedWith401() {
        register("jwt-victim@example.com", "TRAVELER", "secret123");
        String realToken = accounts.login("jwt-victim@example.com", "secret123");
        String userId = (String) login("jwt-victim@example.com", "secret123").getBody().get("id");

        // 1. alg=none, unsigned, claiming ADMIN.
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"" + userId + "\",\"role\":\"ADMIN\","
                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}");
        String algNone = header + "." + payload + ".";

        // 2. Valid signature scheme but the WRONG key.
        String wrongKey = Jwts.builder().subject(userId).claim("role", "ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("some-other-32-byte-long-secret-key!!".getBytes(StandardCharsets.UTF_8)))
                .compact();

        // 3. Right key, but expired.
        String expired = Jwts.builder().subject(userId).claim("role", "TRAVELER")
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8))).compact();

        // 4. Real token whose payload was swapped for role=ADMIN, signature kept.
        String[] parts = realToken.split("\\.");
        String swapped = parts[0] + "." + b64("{\"sub\":\"" + userId + "\",\"role\":\"ADMIN\","
                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}") + "." + parts[2];

        // 5. A service-to-service token (subject "service:identity"): not a user.
        String serviceToken = Jwts.builder().subject("service:identity")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8))).compact();

        for (String token : List.of(algNone, wrongKey, expired, swapped, serviceToken)) {
            HttpEntity<Void> entity = new HttpEntity<>(TestAccounts.bearer(token));
            assertThat(restTemplate.exchange("/me", HttpMethod.GET, entity, Map.class).getStatusCode())
                    .as("GET /me with %s...", token.substring(0, 12)).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(restTemplate.exchange("/users", HttpMethod.GET, entity, Map.class).getStatusCode())
                    .as("GET /users with %s...", token.substring(0, 12)).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void theHealthEndpointStaysUpButExposesNoInternalDetails() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"")
                .doesNotContain("components").doesNotContain("diskSpace").doesNotContain("PostgreSQL");
    }

    @Test
    void aMalformedJsonBodyDoesNotLeakAStackTrace() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange("/users", HttpMethod.POST,
                new HttpEntity<>("{\"email\": ", headers), String.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(String.valueOf(response.getBody())).doesNotContain("Exception").doesNotContain("com.travelplan")
                .doesNotContain("at org.");
    }

    // ------------------------------------------------------------------- helpers

    private ResponseEntity<Map> register(String email, String role, String password) {
        return restTemplate.postForEntity("/users",
                Map.of("email", email, "password", password, "role", role), Map.class);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return restTemplate.postForEntity("/login", Map.of("email", email, "password", password), Map.class);
    }

    private long countUsers() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
    }

    private String storedHash(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ? AND deleted_at IS NULL", String.class, email);
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
