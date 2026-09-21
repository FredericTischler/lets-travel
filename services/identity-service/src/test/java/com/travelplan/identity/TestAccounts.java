package com.travelplan.identity;

import com.travelplan.identity.entity.User;
import com.travelplan.identity.repository.UserRepository;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared test helper: obtains an ADMIN the legitimate way.
 *
 * <p>{@code POST /users} no longer lets an anonymous caller create an ADMIN
 * (docs/lets-travel-architecture-decisions.md §1 addendum), so the tests that
 * need admin rights cannot create one through the API. The first admin in
 * production comes from the startup bootstrap; here we insert the row directly
 * through {@link UserRepository} with a real BCrypt hash — the same result the
 * bootstrap produces (see {@code BootstrapAdminIntegrationTest} for the
 * bootstrap itself), and then log in through the real {@code POST /login} so
 * the token is a genuine one, not a forged shortcut.</p>
 */
final class TestAccounts {

    static final String DEFAULT_PASSWORD = "secret123";

    private final TestRestTemplate restTemplate;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    TestAccounts(TestRestTemplate restTemplate, UserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder) {
        this.restTemplate = restTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Inserts an active ADMIN row directly (no API involved) and returns its id. */
    UUID createAdmin(String email, String password) {
        User saved = userRepository.save(new User(email, passwordEncoder.encode(password), "ADMIN"));
        return saved.getId();
    }

    UUID createAdmin(String email) {
        return createAdmin(email, DEFAULT_PASSWORD);
    }

    /** Real {@code POST /login}; returns the JWT. */
    String login(String email, String password) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/login", Map.of("email", email, "password", password), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("login of %s", email).isTrue();
        return (String) response.getBody().get("token");
    }

    /** A brand-new ADMIN (unique email), logged in; returns its token. */
    String newAdminToken() {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        createAdmin(email, DEFAULT_PASSWORD);
        return login(email, DEFAULT_PASSWORD);
    }

    /** A brand-new ADMIN, logged in, ready to use as the {@code entity} of an {@code exchange} call. */
    HttpEntity<Void> newAdminEntity() {
        return new HttpEntity<>(bearer(newAdminToken()));
    }

    static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }
}
