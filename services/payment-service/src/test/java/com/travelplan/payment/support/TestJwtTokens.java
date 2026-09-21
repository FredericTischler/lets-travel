package com.travelplan.payment.support;

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
 * This mirrors identity-service's own {@code JwtService} (HS256, subject =
 * user id, {@code role} claim, short expiration) closely enough to exercise
 * {@code TokenValidationService}'s real signature + expiration + role checks
 * end-to-end, without needing identity-service running anywhere in these
 * tests.
 */
public final class TestJwtTokens {

    /** >= 32 bytes once UTF-8 encoded, as required by jjwt/HS256 (RFC 7518 §3.2). */
    public static final String SIGNING_KEY = "test-only-jwt-signing-key-for-payment-tests-0123456789ABCDEF";

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
     * A freshly-signed, currently-valid token whose subject is
     * {@code userId} and whose {@code role} claim is {@code role} — used to
     * exercise the role/ownership split introduced by
     * docs/lets-travel-architecture-decisions.md §1
     * ({@code TokenValidationService#requireOwnerOrAdmin}).
     */
    public static String tokenFor(UUID userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
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

    /**
     * The service-to-service token identity-service mints to call
     * {@code DELETE /payments/by-user/{userId}} (subject
     * {@code "service:identity"}, no email claim). Same signing key / 15 min
     * validity / HS256 pattern as {@link #validToken()}.
     */
    public static String serviceToken() {
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("service:identity")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * The service-to-service token travel-service mints to read
     * {@code GET /payments/income} (subject {@code "service:travel"}, no role
     * claim). Same signing key / 15 min validity / HS256 pattern as
     * {@link #serviceToken()}.
     */
    public static String travelServiceToken() {
        SecretKey key = Keys.hmacShaKeyFor(SIGNING_KEY.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("service:travel")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}