package com.travelplan.travel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Env vars fail-fast" (CLAUDE.md), proven on the real application.yml with an
 * environment that has NONE of the variables: every secret / base URL must be
 * unresolvable (=> the service refuses to start), and only the non-secret
 * tunables (and the deliberately optional Elasticsearch endpoint, see the
 * comment in application.yml) may fall back.
 *
 * <p>Regression guard (security audit G3): {@code ${VAR:?message}} is Docker
 * Compose syntax; Spring reads what follows the first ':' as a literal DEFAULT,
 * so a missing NEO4J_PASSWORD silently resolved to "?NEO4J_PASSWORD is
 * required". The yml now uses bare {@code ${VAR}}. Mirrors identity-service's
 * and payment-service's test of the same name.</p>
 */
class ConfigFailFastTest {

    private static StandardEnvironment environmentWith(Map<String, Object> variables) throws IOException {
        // Hermetic: no system properties / real OS env vars (a developer shell
        // may well export NEO4J_PASSWORD or JWT_SIGNING_KEY), and not the
        // src/test/resources/application.properties either (only the yml is loaded).
        StandardEnvironment env = new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources sources) {
                // intentionally empty
            }
        };
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        yaml.forEach(env.getPropertySources()::addLast);
        env.getPropertySources().addFirst(new MapPropertySource("test-vars", variables));
        return env;
    }

    @Test
    void everySecretAndBaseUrlIsRequired() throws IOException {
        StandardEnvironment empty = environmentWith(Map.of());

        for (String key : List.of("spring.neo4j.uri", "spring.neo4j.authentication.username",
                "spring.neo4j.authentication.password", "jwt.signing-key", "payment-service.url")) {
            assertThatThrownBy(() -> empty.getProperty(key))
                    .as("property %s must be unresolvable without its env var", key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Could not resolve placeholder");
        }
    }

    @Test
    void eachNeo4jVariableIsIndividuallyRequired() throws IOException {
        // One variable missing at a time must still abort: guards against a
        // half-fixed file where the host is bare but the port is still `:?`.
        Map<String, Object> all = Map.of("NEO4J_HOST", "h", "NEO4J_PORT", "1",
                "NEO4J_USERNAME", "neo4j", "NEO4J_PASSWORD", "p");
        for (String missing : all.keySet()) {
            Map<String, Object> partial = new HashMap<>(all);
            partial.remove(missing);
            StandardEnvironment env = environmentWith(partial);
            assertThatThrownBy(() -> {
                env.getProperty("spring.neo4j.uri");
                env.getProperty("spring.neo4j.authentication.username");
                env.getProperty("spring.neo4j.authentication.password");
            }).as("missing %s must abort", missing)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Could not resolve placeholder '" + missing + "'");
        }
    }

    @Test
    void theNonSecretTunablesDefaultToInertValues() throws IOException {
        StandardEnvironment empty = environmentWith(Map.of());

        assertThat(empty.getProperty("cors.allowed-origins")).contains("localhost");
        assertThat(empty.getProperty("server.port")).isEqualTo("8080");
        assertThat(empty.getProperty("dashboard.reference-currency")).isEqualTo("EUR");
        assertThat(empty.getProperty("elasticsearch.host")).isEqualTo("localhost");
    }

    @Test
    void withTheVariablesPresentEverythingResolvesToTheirValues() throws IOException {
        StandardEnvironment env = environmentWith(Map.of(
                "NEO4J_HOST", "h", "NEO4J_PORT", "7687", "NEO4J_USERNAME", "neo4j", "NEO4J_PASSWORD", "p",
                "JWT_SIGNING_KEY", "k", "PAYMENT_SERVICE_URL", "http://payment"));

        assertThat(env.getProperty("spring.neo4j.uri")).isEqualTo("bolt://h:7687");
        assertThat(env.getProperty("spring.neo4j.authentication.username")).isEqualTo("neo4j");
        assertThat(env.getProperty("spring.neo4j.authentication.password")).isEqualTo("p");
        assertThat(env.getProperty("jwt.signing-key")).isEqualTo("k");
        assertThat(env.getProperty("payment-service.url")).isEqualTo("http://payment");
    }

    @Test
    void healthDetailsAreNeverShownToAnonymousCallers() throws IOException {
        assertThat(environmentWith(Map.of()).getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }
}
