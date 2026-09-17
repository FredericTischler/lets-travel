package com.travelplan.travel.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast guard for required Neo4j connection environment variables.
 *
 * Spring Boot's {@code :?} syntax in application.yml already prevents startup
 * when a variable is absent (throws {@link IllegalArgumentException} with a
 * clear placeholder name). This class provides an explicit, human-readable
 * log entry that names WHICH variable is missing and WHY it is required,
 * making container log triage faster than reading a raw Spring exception —
 * same mechanism as {@code DataSourceConfig} in identity-service/payment-service.
 *
 * Variables are injected by Docker Compose from Vault (secret/travel/db):
 *   NEO4J_HOST     <- host
 *   NEO4J_PORT     <- port
 *   NEO4J_USERNAME <- username
 *   NEO4J_PASSWORD <- password
 *
 * No "dbname" key: Neo4j Community Edition has a single default database
 * (no multi-database support), unlike the Postgres-backed services.
 *
 * Neo4j Community Edition constraint: there is exactly one administrative
 * account (username "neo4j"). This service connects using those admin
 * credentials — not a dedicated, scoped account — because Community Edition
 * does not support per-service accounts (no RBAC, no multi-tenant users).
 * This is a known, documented limitation of the edition, not an oversight;
 * see ansible/roles/neo4j/tasks/main.yml for the same rationale on the infra side.
 *
 * If any variable is absent, Spring Boot fails before this bean is instantiated
 * (the :? in application.yml fires first). This @PostConstruct is a secondary
 * check that runs after binding to confirm resolved values are non-blank.
 */
@Configuration
public class Neo4jConnectionConfig {

    private static final Logger log = LoggerFactory.getLogger(Neo4jConnectionConfig.class);

    @Value("${NEO4J_HOST}")
    private String neo4jHost;

    @Value("${NEO4J_PORT}")
    private String neo4jPort;

    @Value("${NEO4J_USERNAME}")
    private String neo4jUsername;

    // NEO4J_PASSWORD is intentionally NOT logged. Its presence is validated by
    // the :? placeholder in application.yml; we do not re-inject it here to
    // avoid any accidental exposure in heap dumps or debug output.

    @PostConstruct
    void validateNeo4jConnectionVariables() {
        assertNonBlank("NEO4J_HOST", neo4jHost);
        assertNonBlank("NEO4J_PORT", neo4jPort);
        assertNonBlank("NEO4J_USERNAME", neo4jUsername);
        log.info("Neo4j connection configuration validated: bolt://{}:{}", neo4jHost, neo4jPort);
    }

    private static void assertNonBlank(String variableName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required environment variable '" + variableName + "' is absent or blank. "
                    + "This variable must be injected by Docker Compose from Vault "
                    + "(secret/travel/db). The service cannot start without it.");
        }
    }
}