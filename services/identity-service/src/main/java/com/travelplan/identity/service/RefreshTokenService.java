package com.travelplan.identity.service;

import com.travelplan.identity.entity.RefreshToken;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.exception.InvalidTokenException;
import com.travelplan.identity.repository.RefreshTokenRepository;
import com.travelplan.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues, redeems and revokes refresh tokens (V7__add_refresh_tokens.sql) —
 * the mechanism {@link JwtService}'s class Javadoc previously flagged as out
 * of scope ("No refresh token, no revocation/blacklist").
 *
 * <p>A refresh token is an opaque, high-entropy random value — deliberately
 * <b>not</b> a JWT: unlike the access token, its only job is to be redeemed
 * exactly once for a new access token, so it needs no claims, and storing
 * only its hash lets a leaked database not hand out working credentials
 * (same principle as {@code User.passwordHash}). The raw value exists in
 * plaintext only in memory, for the one response that hands it to the
 * client — never logged, never persisted.</p>
 *
 * <p><b>Rotation, not a fixed session:</b> {@link #redeem} revokes the token
 * it was called with in the same transaction it issues a fresh one. This
 * means each refresh token is single-use: replaying an already-redeemed
 * (or logged-out) one is indistinguishable from an unknown one, both surface
 * as the same {@link InvalidTokenException} the rest of this codebase
 * already uses for "no valid credential here" — same non-disclosure
 * philosophy as {@code POST /login} and {@code GET /me}
 * ({@link AuthService}).</p>
 */
@Service
@Transactional(readOnly = true)
public class RefreshTokenService {

    /**
     * Long-lived relative to the 15-minute access token ({@link JwtService}),
     * short enough that a leaked-and-unused refresh token stops working on
     * its own. Hardcoded, not env-configurable — same choice already made for
     * {@code JwtService#TOKEN_VALIDITY}.
     */
    private static final Duration REFRESH_TOKEN_VALIDITY = Duration.ofDays(7);

    /** 256 bits of entropy — as strong as the HS256 signing key this project already requires. */
    private static final int RAW_TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
    }

    /**
     * Generate, persist (hashed) and return a new refresh token for {@code user}.
     *
     * @return the raw token value — the only time it ever exists outside this
     *         method's stack frame, embedded verbatim in the API response
     */
    @Transactional
    public String issue(User user) {
        byte[] randomBytes = new byte[RAW_TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RefreshToken entity = new RefreshToken(user.getId(), hash(rawToken), now.plus(REFRESH_TOKEN_VALIDITY));
        refreshTokenRepository.save(entity);
        return rawToken;
    }

    /**
     * Redeem {@code rawToken} for the active user it was issued to, revoking
     * it in the same transaction (rotation — see class Javadoc).
     *
     * <p>Every check (unknown/expired/inactive-user) runs <b>before</b> the
     * revoking write: this method is called from within {@code AuthService}'s
     * own {@code @Transactional} methods (same physical transaction, default
     * propagation), and throwing an unchecked exception after a write marks
     * the whole transaction rollback-only — a "revoke defensively, then
     * throw anyway" ordering would have silently discarded the revoke on
     * every failure path, the opposite of what it was meant to guarantee.
     * Revoking only on the path that actually returns a user sidesteps that
     * entirely, rather than fighting it with a second transaction.</p>
     *
     * @throws InvalidTokenException if {@code rawToken} is blank, unknown,
     *         already revoked/redeemed, expired, or its user is no longer
     *         active — all indistinguishable to the caller
     */
    @Transactional
    public User redeem(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException();
        }
        RefreshToken entity = refreshTokenRepository.findActiveByTokenHash(hash(rawToken))
                .orElseThrow(InvalidTokenException::new);

        if (entity.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new InvalidTokenException();
        }
        User user = userRepository.findActiveById(entity.getUserId())
                .orElseThrow(InvalidTokenException::new);

        entity.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        refreshTokenRepository.save(entity);
        return user;
    }

    /**
     * Revoke {@code rawToken} if it is currently active — a no-op, not an
     * error, if it is already revoked/redeemed or was never issued: logout
     * must not leak which of those is the case (same principle as every
     * other credential check in this service).
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findActiveByTokenHash(hash(rawToken))
                .ifPresent(entity -> {
                    entity.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    refreshTokenRepository.save(entity);
                });
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a JDK-guaranteed algorithm (every conforming JVM ships it) — this is
            // unreachable, not a real failure mode to handle for the caller.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
