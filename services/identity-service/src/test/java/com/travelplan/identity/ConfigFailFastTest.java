package com.travelplan.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Env vars fail-fast" (CLAUDE.md), proven on the real application.yml with an
 * environment that has NONE of the variables: every secret / base URL must be
 * unresolvable (=> the service refuses to start), and only the two optional
 * bootstrap variables and the non-secret CORS list may fall back.
 *
 * <p>Regression guard: {@code ${VAR:?message}} is Docker Compose syntax; Spring
 * reads what follows the first ':' as a literal DEFAULT, so it silently
 * resolved to "?message" instead of failing. The yml now uses bare
 * {@code ${VAR}}.</p>
 */
class ConfigFailFastTest {

    private static StandardEnvironment environmentWith(Map<String, Object> variables) throws IOException {
        // Hermetic: no system properties / real OS env vars (a developer shell
        // may well export DB_HOST or JWT_SIGNING_KEY).
        StandardEnvironment env = new StandardEnvironment() {
            @Override
            protected void customizePropertySources(org.springframework.core.env.MutablePropertySources sources) {
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

        for (String key : List.of("spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password", "jwt.signing-key", "payment-service.url")) {
            assertThatThrownBy(() -> empty.getProperty(key))
                    .as("property %s must be unresolvable without its env var", key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Could not resolve placeholder");
        }
    }

    @Test
    void theOptionalBootstrapVariablesAndCorsDefaultToInertValues() throws IOException {
        StandardEnvironment empty = environmentWith(Map.of());

        assertThat(empty.getProperty("bootstrap-admin.email")).isEmpty();
        assertThat(empty.getProperty("bootstrap-admin.password")).isEmpty();
        assertThat(empty.getProperty("cors.allowed-origins")).contains("localhost");
    }

    @Test
    void withTheVariablesPresentEverythingResolvesToTheirValues() throws IOException {
        StandardEnvironment env = environmentWith(Map.of(
                "DB_HOST", "h", "DB_PORT", "1", "DB_NAME", "n", "DB_USERNAME", "u", "DB_PASSWORD", "p",
                "JWT_SIGNING_KEY", "k", "PAYMENT_SERVICE_URL", "http://payment"));

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://h:1/n");
        assertThat(env.getProperty("payment-service.url")).isEqualTo("http://payment");
    }
}
