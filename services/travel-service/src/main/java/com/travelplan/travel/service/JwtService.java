package com.travelplan.travel.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Validates JWTs issued by identity-service.
 *
 * <p><b>Verify-only for user tokens:</b> travel-service is not an
 * identity provider — it never has a login endpoint and never mints a user
 * token. It only needs to confirm that a token presented by a caller was really
 * signed by identity-service and has not expired, hence {@link #validate(String)}
 * and no {@code generateToken}. The sole exception is
 * {@link #generateServiceToken()}, the service-to-service token used to read
 * payment-service's income aggregate for the dashboards (same exception as
 * payment-service's own {@code JwtService}).</p>
 *
 * <p><b>Shared HS256 secret:</b> identity-service signs with HS256 (symmetric),
 * so verifying here requires holding the exact same signing secret — see
 * {@code JWT_SIGNING_KEY} in {@code docker-compose.travel.yml}, which must
 * stay in sync with the value used by identity-service. This is a deliberate
 * consequence of the HS256 choice documented in identity-service's own
 * JwtService: it is only valid as long as every verifier is a service that
 * can be trusted with the signing secret itself (as opposed to RS256, where
 * a verifier would only need a public key).</p>
 *
 * <p>No refresh token, no revocation/blacklist, no roles/permissions read
 * from the token — travel-service only cares whether the token is valid,
 * not who the caller is or what they are allowed to do.</p>
 */
@Service
public class JwtService {

    /** Subject of the token minted by {@link #generateServiceToken()}; payment-service matches on it. */
    public static final String SERVICE_TRAVEL_SUBJECT = "service:travel";

    private static final Duration SERVICE_TOKEN_VALIDITY = Duration.ofMinutes(15);

    @Value("${jwt.signing-key}")
    private String signingKeySecret;

    private SecretKey signingKey;

    /**
     * Builds the HMAC key once at startup. jjwt enforces RFC 7518 §3.2 for
     * HS256: the key must be >= 256 bits (32 bytes) once UTF-8 encoded, or
     * {@link io.jsonwebtoken.security.WeakKeyException} aborts startup here —
     * fail-fast, not a silent weak default.
     */
    @PostConstruct
    void buildSigningKey() {
        byte[] keyBytes = signingKeySecret.getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Issue a short-lived (15 min) service-to-service token identifying this
     * service to payment-service, for the sole purpose of reading the income
     * aggregate {@code GET /payments/income} (see {@link PaymentStatsClient}).
     * Subject = {@link #SERVICE_TRAVEL_SUBJECT}, no role, no user identity —
     * so every user-facing endpoint (here and in payment-service) refuses it,
     * and payment-service accepts it on that one route only. Signed with the same
     * shared HS256 secret, mirroring {@code service:payment} in the other direction.
     */
    public String generateServiceToken() {
        Instant now = Instant.now(Clock.systemUTC());
        return Jwts.builder()
                .subject(SERVICE_TRAVEL_SUBJECT)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(SERVICE_TOKEN_VALIDITY)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verify signature and expiration and return the claims.
     *
     * @throws JwtException if the token is malformed, expired, or the
     *         signature does not match — callers must map every subtype to
     *         the same generic 401 (no distinction leaked to the client)
     * @throws IllegalArgumentException if {@code token} is null/blank
     */
    public Claims validate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}