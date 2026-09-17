package com.travelplan.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Context load + DB connectivity smoke test.
 *
 * Uses Testcontainers to spin up a real Postgres instance (same major version
 * as production: 17.x). DynamicPropertySource injects the connection details
 * so that the :? fail-fast guards in application.yml are satisfied without
 * requiring an external Docker Compose stack.
 *
 * This test validates:
 *   1. Spring application context loads without errors.
 *   2. DataSourceConfig.validateDataSourceVariables() passes.
 *   3. Flyway applies V1__init.sql (table + index) against a live Postgres.
 *   4. HikariCP acquires a connection (Hibernate ddl-auto=validate succeeds).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class IdentityServiceApplicationTests {

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
    }

    @Test
    void contextLoads() {
        // If the context starts, DataSourceConfig validated, Flyway migrated,
        // and HikariCP connected successfully. No assertion needed beyond load.
    }
}