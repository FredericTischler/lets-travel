package com.travelplan.payment;

import com.travelplan.payment.support.TestJwtTokens;
import com.travelplan.payment.support.TestProviderCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context load + DB connectivity smoke test.
 *
 * Uses Testcontainers to spin up a real Postgres instance (same major version
 * as production: 17.x). DynamicPropertySource injects the connection details
 * plus a test JWT signing key so that every fail-fast `${VAR}` placeholder in
 * application.yml (DB_* and JWT_SIGNING_KEY) is satisfied without requiring
 * an external Docker Compose stack.
 *
 * This test validates:
 *   1. Spring application context loads without errors.
 *   2. DataSourceConfig.validateDataSourceVariables() passes.
 *   3. Flyway applies V1__init.sql + V2__add_user_id.sql against a live
 *      Postgres, and the resulting `payments.amount` column is an exact
 *      numeric type (NUMERIC), never float/double — verified both via
 *      information_schema metadata and via an actual insert/read round-trip
 *      of a many-decimal value (user_id supplied since V2 made it NOT NULL).
 *   4. /actuator/health returns UP against the real Testcontainers database.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentServiceApplicationTests {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.5-bookworm")
                    .withDatabaseName("payment_db")
                    .withUsername("payment_user")
                    .withPassword("test_password_only");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", () -> String.valueOf(postgres.getMappedPort(5432)));
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("JWT_SIGNING_KEY", () -> TestJwtTokens.SIGNING_KEY);
        registry.add("STRIPE_API_KEY", () -> TestProviderCredentials.STRIPE_API_KEY);
        registry.add("STRIPE_SECRET_KEY", () -> TestProviderCredentials.STRIPE_SECRET_KEY);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> TestProviderCredentials.STRIPE_WEBHOOK_SECRET);
        registry.add("PAYPAL_CLIENT_ID", () -> TestProviderCredentials.PAYPAL_CLIENT_ID);
        registry.add("PAYPAL_CLIENT_SECRET", () -> TestProviderCredentials.PAYPAL_CLIENT_SECRET);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // If the context starts, DataSourceConfig validated, Flyway migrated,
        // and HikariCP connected successfully. No assertion needed beyond load.
    }

    @Test
    @SuppressWarnings("unchecked")
    void actuatorHealthReportsUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        // `show-details: never` (security audit G6): an anonymous caller gets the status only.
        assertThat(response.getBody()).doesNotContainKey("components");
    }

    @Test
    void amountColumnIsExactNumericNotFloatingPoint() {
        // 1. Metadata check: information_schema must report the "numeric"
        //    data type for payments.amount, not "double precision"/"real".
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'payments' AND column_name = 'amount'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");

        // 2. Round-trip check: a value with many decimal digits must survive
        //    an insert/read cycle unaltered. Binary floating point types
        //    (float/double) cannot represent this exactly; NUMERIC can.
        BigDecimal preciseAmount = new BigDecimal("1234.123456789012");
        jdbcTemplate.update(
                "INSERT INTO payments (user_id, amount, currency) VALUES (?, ?, ?)",
                UUID.randomUUID(), preciseAmount, "EUR");

        BigDecimal stored = jdbcTemplate.queryForObject(
                "SELECT amount FROM payments WHERE currency = 'EUR'",
                BigDecimal.class);

        assertThat(stored).isEqualByComparingTo(preciseAmount);
    }
}