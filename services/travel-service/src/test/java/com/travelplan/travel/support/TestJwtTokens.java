package com.travelplan.travel.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Test-only JWT helper shared by integration tests.
 *
 * Signs tokens with {@link #SIGNING_KEY}, the same secret every test class
 * registers as {@code JWT_SIGNING_KEY} via {@code @DynamicPropertySource}.
 * Mirrors identity-service's own {@code JwtService} (HS256, subject = user
 * id, {@code role} claim, short expiration) closely enough to exercise
 * travel-service's JwtService signature/expiration/role checks end-to-end,
 * without needing identity-service running anywhere in these tests. Copied
 * from payment-service's {@code TestJwtTokens} — same pattern, no
 * {@code serviceToken()} here since travel-service has no service-to-service
 * caller to impersonate.
 */
public final class TestJwtTokens {

    /** >= 32 bytes once UTF-8 encoded, as required by jjwt/HS256 (RFC 7518 §3.2). */
    public static final String SIGNING_KEY = "test-only-jwt-signing-key-for-travel-service-tests-0123456789";

    private TestJwtTokens() {
    }

    /**
     * A freshly-signed, currently-valid token (15 min validity, arbitrary
     * random subject, {@code role=ADMIN} claim — mirrors every account
     * issued by identity-service today).
     */
    public static String validToken() {
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", "ADMIN")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * A freshly-signed, currently-valid token carrying the given {@code role}
     * claim (e.g. {@code "TRAVEL_MANAGER"}, {@code "TRAVELER"}) — exercises
     * the role split introduced by docs/lets-travel-architecture-decisions.md
     * §1 (read open to any role, mutation restricted to ADMIN/TRAVEL_MANAGER).
     */
    public static String tokenWithRole(String role) {
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * A freshly-signed, currently-valid token carrying the given {@code role}
     * claim and a caller-chosen {@code subject} — needed to exercise
     * ownership-aware endpoints (docs/lets-travel-architecture-decisions.md
     * §2), where a test needs to know the exact user id a token represents
     * (e.g. to set as a destination's {@code managerId} and later assert that
     * same caller can/cannot manage it).
     */
    public static String tokenWithRoleAndSubject(String role, UUID subject) {
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject.toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * The service-to-service token payment-service mints to report a
     * subscription payment's outcome (subject {@code service:payment}, no
     * role claim) — the only credential
     * {@code POST /internal/subscriptions/{ref}/payment-result} accepts.
     */
    public static String paymentServiceToken() {
        return tokenWithSubject("service:payment");
    }

    /** A valid, role-less token with an arbitrary subject (e.g. a service identity). */
    public static String tokenWithSubject(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * A freshly-signed, currently-valid token with no {@code role} claim at
     * all — exercises {@code TokenValidationService}'s least-privilege
     * rejection (403) of an otherwise-valid token that simply never claims
     * the ADMIN role.
     */
    public static String validTokenWithoutRole() {
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
