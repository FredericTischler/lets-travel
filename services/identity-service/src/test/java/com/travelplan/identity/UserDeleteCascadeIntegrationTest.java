package com.travelplan.identity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the happy path of the cascade delete:
 * {@code DELETE /users/{id}} -> payment-service's
 * {@code DELETE /payments/by-user/{userId}} (see
 * {@code UserController#delete} / {@code PaymentServiceClient}).
 *
 * payment-service is stubbed with a plain JDK {@link HttpServer} (no new test
 * dependency needed) so the real {@code PaymentServiceClient} / {@code RestClient}
 * code path is exercised end to end over a real socket, not a mocked bean.
 * The resilience/failure path (payment-service unreachable) is covered
 * separately by {@link UserDeleteCascadeFailureIntegrationTest}, since it
 * needs a different {@code PAYMENT_SERVICE_URL} and therefore a different
 * Spring application context.
 *
 * Uses Testcontainers (postgres:17.5-bookworm, same image as production).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserDeleteCascadeIntegrationTest {

    private static final String SIGNING_KEY = "test-only-signing-key-must-be-at-least-32-bytes-long";

    // Started eagerly in a static field initializer (not @BeforeAll) so it is
    // guaranteed to be listening before Spring resolves the lazy
    // @DynamicPropertySource supplier below that references its port.
    private static final HttpServer STUB_PAYMENT_SERVICE = startStub();
    private static final BlockingQueue<HttpExchange> RECEIVED_REQUESTS = new ArrayBlockingQueue<>(10);

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/payments/by-user/", exchange -> {
                RECEIVED_REQUESTS.add(exchange);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start stub payment-service", ex);
        }
    }

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
        registry.add("JWT_SIGNING_KEY", () -> SIGNING_KEY);
        registry.add("PAYMENT_SERVICE_URL",
                () -> "http://localhost:" + STUB_PAYMENT_SERVICE.getAddress().getPort());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void deletingAUserCascadesToPaymentServiceWithAServiceScopedToken() throws InterruptedException {
        String email = "cascade-target@example.com";
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/users", Map.of("email", email, "password", "secret123"), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String userId = (String) createResponse.getBody().get("id");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/users/" + userId, HttpMethod.DELETE, authenticatedEntity(), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        HttpExchange received = RECEIVED_REQUESTS.poll(5, TimeUnit.SECONDS);
        assertThat(received).as("payment-service stub should have received a request").isNotNull();
        assertThat(received.getRequestMethod()).isEqualTo("DELETE");
        assertThat(received.getRequestURI().getPath()).isEqualTo("/payments/by-user/" + userId);

        String authorizationHeader = received.getRequestHeaders().getFirst("Authorization");
        assertThat(authorizationHeader).startsWith("Bearer ");
        String token = authorizationHeader.substring("Bearer ".length());

        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("service:identity");
    }

    private HttpEntity<Void> authenticatedEntity() {
        String email = "cascade-bystander-" + System.nanoTime() + "@example.com";
        String password = "bystander_password_1";
        restTemplate.postForEntity("/users", Map.of("email", email, "password", password), Map.class);

        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                "/login", Map.of("email", email, "password", password), Map.class);
        String token = (String) loginResponse.getBody().get("token");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return new HttpEntity<>(headers);
    }
}
