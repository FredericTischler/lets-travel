package com.travelplan.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Application entry point.
 * Security autoconfiguration is explicitly excluded: spring-boot-starter-security
 * is on the classpath ONLY to expose a BCryptPasswordEncoder bean (see
 * {@link com.travelplan.identity.config.SecurityConfig}). Without these
 * exclusions, Spring Boot would autoconfigure a default filter chain that
 * protects every existing route with HTTP Basic + a generated password,
 * breaking increment 2's endpoints. No SecurityFilterChain is defined
 * anywhere in this codebase at this stage.
 */
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class
})
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}