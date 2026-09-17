package com.travelplan.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Exposes the password encoder as an injectable bean.
 *
 * This is the ONLY piece of Spring Security wired into the application at
 * this stage: no SecurityFilterChain, no route protection, no filter. See
 * {@link com.travelplan.identity.IdentityServiceApplication} for the
 * autoconfiguration exclusions that keep it that way.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}