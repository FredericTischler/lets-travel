package com.travelplan.payment.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast guard for required DataSource environment variables.
 *
 * Spring Boot's {@code :?} syntax in application.yml already prevents startup
 * when a variable is absent (throws {@link IllegalArgumentException} with a
 * clear placeholder name). This class provides an explicit, human-readable
 * log entry that names WHICH variable is missing and WHY it is required,
 * making container log triage faster than reading a raw Spring exception.
 *
 * Variables are injected by Docker Compose from Vault (secret/payment/db):
 *   DB_HOST     <- host
 *   DB_PORT     <- port
 *   DB_NAME     <- dbname
 *   DB_USERNAME <- username
 *   DB_PASSWORD <- password
 *
 * If any variable is absent, Spring Boot fails before this bean is instantiated
 * (the :? in application.yml fires first). This @PostConstruct is a secondary
 * check that runs after binding to confirm resolved values are non-blank.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${DB_HOST}")
    private String dbHost;

    @Value("${DB_PORT}")
    private String dbPort;

    @Value("${DB_NAME}")
    private String dbName;

    @Value("${DB_USERNAME}")
    private String dbUsername;

    // DB_PASSWORD is intentionally NOT logged. Its presence is validated by
    // the :? placeholder in application.yml; we do not re-inject it here to
    // avoid any accidental exposure in heap dumps or debug output.

    @PostConstruct
    void validateDataSourceVariables() {
        assertNonBlank("DB_HOST", dbHost);
        assertNonBlank("DB_PORT", dbPort);
        assertNonBlank("DB_NAME", dbName);
        assertNonBlank("DB_USERNAME", dbUsername);
        log.info("DataSource configuration validated: jdbc:postgresql://{}:{}/{}",
                dbHost, dbPort, dbName);
    }

    private static void assertNonBlank(String variableName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required environment variable '" + variableName + "' is absent or blank. "
                    + "This variable must be injected by Docker Compose from Vault "
                    + "(secret/payment/db). The service cannot start without it.");
        }
    }
}