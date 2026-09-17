package com.travelplan.travel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * travel-service validates Bearer tokens issued by identity-service (see
 * {@code service.JwtService}) using jjwt directly — no Spring Security
 * dependency is on the classpath, so no autoconfiguration exclusion is
 * required here, unlike identity-service (which pulls in
 * spring-boot-starter-security only for BCrypt). Same posture as
 * payment-service.
 */
@SpringBootApplication
public class TravelServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelServiceApplication.class, args);
    }
}