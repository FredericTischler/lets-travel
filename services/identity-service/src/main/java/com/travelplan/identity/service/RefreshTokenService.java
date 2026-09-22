package com.travelplan.identity.service;

import com.travelplan.identity.entity.RefreshToken;
import com.travelplan.identity.exception.InvalidTokenException;
import com.travelplan.identity.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issuance, rotation and revocation of refresh tokens — a partial mitigation
 * of security audit G10 (docs/lets-travel-architecture-decisions.md addendum
 * "Refresh token"). {@link JwtService}'s 15-minute access token is unchanged;
 * this class only adds a longer-lived credential a client can exchange for a
 * fresh access token without re-entering a password.
 *
 * <p><b>The value.</b> A random opaque string — never a JWT, never anything
 * the server can recompute from its parts — generated with
 * {@link SecureRandom} ({@value #TOKEN_BYTES} bytes = 256 bits of entropy,
 * base64url-encoded without padding so it is safe in a JSON body or a URL).
 * Only its SHA-256 hash (hex) is ever persisted; the plaintext value exists
 * solely in the HTTP response that hands it to the client at issuance and is
 * never logged.</p>
 *
 * <p><b>Rotation</b> ({@link #rotate}). Presenting a refresh token consumes
 * it: the matching row is revoked in the very call that mints its
 * replacement, so a captured-but-already-used token cannot be replayed
 * (single-use tokens, the usual mitigation for refresh-token theft — full
 * theft detection/family revocation is out of scope for this increment, see
 * the ADR addendum). An absent, expired, or already-revoked token is
 * indistinguishable to the caller — all three collapse to
 * {@link InvalidTokenException} (401), the same non-disclosure philosophy
 * this service already applies to access tokens and login credentials.</p>
 *
 * <p><b>Lifetime.</b> {@value #REFRESH_TOKEN_VALIDITY_DAYS} days, a named
 * constant like {@link JwtService}'s {@code TOKEN_VALIDITY} — not an env var,
 * it is a tunable, not a secret.</p>
 */
@Service
@Transactional(readOnly = true)
public class RefreshTokenService {

    private static final int REFRESH_TOKEN_VALIDITY_DAYS = 7;
    static final Duration REFRESH_TOKEN_VALIDITY = Duration.ofDays(REFRESH_TOKEN_VALIDITY_DAYS);

    /** 256 bits — well above the "&gt;= 32 octets" floor. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Issue a brand-new refresh token for {@code userId}.
     *
     * @return the plaintext value — the only time it ever exists outside this
     *         method's local scope; only its hash is persisted
     */
    @Transactional
    public String issue(UUID userId) {
        String token = generateOpaqueToken();
        OffsetDateTime now = OffsetDateTime.now();
        RefreshToken entity = new RefreshToken(userId, hash(token), now, now.plus(REFRESH_TOKEN_VALIDITY));
        refreshTokenRepository.save(entity);
        return token;
    }

    /**
     * Consume {@code presentedToken}: revoke the row it matches so it cannot
     * be replayed, and return the user id it was issued for so the caller
     * (see {@link AuthService#refresh}) can mint its replacement.
     *
     * @throws InvalidTokenException if no row matches {@code presentedToken}'s
     *         hash, or the matching row is expired or already revoked
     */
    @Transactional
    public UUID rotate(String presentedToken) {
        RefreshToken entity = findValid(presentedToken);
        entity.setRevokedAt(OffsetDateTime.now());
        // the dirty check within the transaction persists the change automatically
        return entity.getUserId();
    }

    /**
     * Revoke the row matching {@code presentedToken}, if any exists and is
     * not already revoked. Never throws — {@code POST /auth/logout} always
     * answers 204 regardless of whether the token existed, was already
     * revoked, or is expired: no oracle on refresh-token existence.
     */
    @Transactional
    public void revokeIfPresent(String presentedToken) {
        refreshTokenRepository.findByTokenHash(hash(presentedToken))
                .filter(entity -> entity.getRevokedAt() == null)
                .ifPresent(entity -> entity.setRevokedAt(OffsetDateTime.now()));
    }

    private RefreshToken findValid(String presentedToken) {
        RefreshToken entity = refreshTokenRepository.findByTokenHash(hash(presentedToken))
                .orElseThrow(InvalidTokenException::new);
        if (entity.getRevokedAt() != null || entity.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidTokenException();
        }
        return entity;
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandatory for every JDK implementation (Java Cryptography
            // Architecture Standard Algorithm Name spec) — this can never happen.
            throw new IllegalStateException("SHA-256 must be available on every JVM", ex);
        }
    }
}
