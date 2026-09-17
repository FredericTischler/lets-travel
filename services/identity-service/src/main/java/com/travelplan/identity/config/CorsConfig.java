package com.travelplan.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for browser clients of the admin dashboard: the Angular
 * dev server (http://localhost:4200) and, once routed through Traefik, the
 * production dashboard (https://admin.localhost).
 *
 * This service has no Spring Security filter chain (see {@link SecurityConfig}
 * and {@link com.travelplan.identity.IdentityServiceApplication}), so CORS is
 * wired the plain Spring MVC way — {@link WebMvcConfigurer#addCorsMappings} —
 * rather than via a Spring Security {@code CorsConfigurationSource}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE")
                .allowedHeaders("Authorization", "Content-Type");
    }
}