package com.travelplan.payment;

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
 * unresolvable (=> the service refuses to start); only non-secret tunables may
 * fall back.
 *
 * <p>Regression guard (security audit G3): {@code ${VAR:?message}} is Docker
 * Compose syntax; Spring reads what follows the first ':' as a literal DEFAULT,
 * so a missing DB_PASSWORD silently resolved to "?DB_PASSWORD is required".
 * The yml now uses bare {@code ${VAR}}. Mirrors identity-service's test of the
 * same name.</p>
 */
class ConfigFailFastTest {

    private static StandardEnvironment environmentWith(Map<String, Object> variables) throws IOException {
        // Hermetic: no system properties / real OS env vars, and not the
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

    private static final Map<String, Object> ALL = Map.ofEntries(
            Map.entry("DB_HOST", "h"), Map.entry("DB_PORT", "1"), Map.entry("DB_NAME", "n"),
            Map.entry("DB_USERNAME", "u"), Map.entry("DB_PASSWORD", "p"),
            Map.entry("JWT_SIGNING_KEY", "k"),
            Map.entry("STRIPE_API_KEY", "a"), Map.entry("STRIPE_SECRET_KEY", "s"),
            Map.entry("STRIPE_WEBHOOK_SECRET", "w"),
            Map.entry("PAYPAL_CLIENT_ID", "i"), Map.entry("PAYPAL_CLIENT_SECRET", "c"),
            Map.entry("TRAVEL_SERVICE_URL", "http://travel"));

    @Test
    void everySecretAndBaseUrlIsRequired() throws IOException {
        StandardEnvironment empty = environmentWith(Map.of());

        for (String key : List.of("spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password", "jwt.signing-key", "stripe.api-key", "stripe.secret-key",
                "stripe.webhook-secret", "paypal.client-id", "paypal.client-secret", "travel-service.url")) {
            assertThatThrownBy(() -> empty.getProperty(key))
                    .as("property %s must be unresolvable without its env var", key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Could not resolve placeholder");
        }
    }

    @Test
    void eachDatabaseVariableIsIndividuallyRequired() throws IOException {
        // One variable missing at a time must still abort: guards against a
        // half-fixed file where some DB_* are bare and others still `:?`.
        for (String missing : List.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD")) {
            Map<String, Object> partial = new HashMap<>(ALL);
            partial.remove(missing);
            StandardEnvironment env = environmentWith(partial);
            assertThatThrownBy(() -> {
                env.getProperty("spring.datasource.url");
                env.getProperty("spring.datasource.username");
                env.getProperty("spring.datasource.password");
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
    }

    @Test
    void withTheVariablesPresentEverythingResolvesToTheirValues() throws IOException {
        StandardEnvironment env = environmentWith(ALL);

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://h:1/n");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("u");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p");
        assertThat(env.getProperty("travel-service.url")).isEqualTo("http://travel");
    }

    @Test
    void healthDetailsAreNeverShownToAnonymousCallers() throws IOException {
        assertThat(environmentWith(Map.of()).getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }
}
