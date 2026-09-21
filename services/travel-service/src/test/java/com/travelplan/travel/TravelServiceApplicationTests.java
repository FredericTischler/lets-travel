package com.travelplan.travel;

import com.travelplan.travel.support.TestJwtTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context load + DB connectivity smoke test.
 *
 * Uses Testcontainers to spin up a real Neo4j instance (same image as
 * production: neo4j:5.26.6-community, cf. ansible/roles/neo4j/defaults/main.yml).
 * DynamicPropertySource injects NEO4J_HOST/NEO4J_PORT/NEO4J_USERNAME/NEO4J_PASSWORD
 * and JWT_SIGNING_KEY, satisfying application.yml's required placeholders
 * without requiring an external Docker Compose stack — same pattern as
 * payment-service's PaymentServiceApplicationTests.
 *
 * This test validates:
 *   1. Spring application context loads without errors.
 *   2. Neo4jConnectionConfig.validateNeo4jConnectionVariables() passes.
 *   3. Neo4jSchemaInitializer's CommandLineRunner creates the Destination.id
 *      uniqueness constraint against a live Neo4j instance.
 *   4. /actuator/health returns UP and exposes no component detail to an anonymous caller.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class TravelServiceApplicationTests {

    @Container
    static final Neo4jContainer<?> neo4j =
            new Neo4jContainer<>("neo4j:5.26.6-community")
                    .withAdminPassword("test_password_only");

    @DynamicPropertySource
    static void registerNeo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("NEO4J_HOST", neo4j::getHost);
        registry.add("NEO4J_PORT", () -> String.valueOf(neo4j.getMappedPort(7687)));
        registry.add("NEO4J_USERNAME", () -> "neo4j");
        registry.add("NEO4J_PASSWORD", neo4j::getAdminPassword);
        // /actuator/health doesn't require auth, but JwtService still builds
        // its signing key eagerly at startup (@PostConstruct), so the
        // placeholder must resolve regardless.
        registry.add("JWT_SIGNING_KEY", () -> TestJwtTokens.SIGNING_KEY);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // If the context starts, Neo4jConnectionConfig validated, the schema
        // constraint was created, and the Neo4j driver connected successfully.
        // No assertion needed beyond load.
    }

    @Test
    void actuatorHealthReportsUpWithoutExposingInternalDetailsToAnAnonymousCaller() {
        // `show-details: never` (security audit G6): Traefik routes /actuator/health
        // publicly, so an anonymous caller only gets the overall status. Compose/K8s
        // probes only look at the HTTP status / "UP". The Neo4j indicator still feeds
        // the aggregate (a dead Neo4j turns it DOWN), it is just not itemised.
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"")
                .doesNotContain("components").doesNotContain("neo4j").doesNotContain("diskSpace");
    }
}