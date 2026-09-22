package com.travelplan.identity.service;

import java.util.Locale;

/**
 * Canonical form of an email address, shared by every place in this service
 * that compares or stores one case/whitespace-insensitively (security audit
 * G12 — docs/lets-travel-architecture-decisions.md addendum "Normalisation
 * des emails").
 *
 * <p>{@code trim} + lowercase, {@link Locale#ROOT} so the rule never varies
 * with the JVM's default locale (the "Turkish I" problem: {@code "I".toLowerCase()}
 * yields {@code "ı"} instead of {@code "i"} under a Turkish locale). This is
 * the exact rule {@link LoginThrottle} already applied for its own throttling
 * keys before this class existed — {@code LoginThrottle#normalise} now
 * delegates here instead of duplicating it, and {@link UserService}/
 * {@link AuthService} reuse the same method so a stored email, a login
 * lookup and a throttle key can never silently disagree on what counts as
 * "the same address".</p>
 */
final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /** @param email may be {@code null}, returned as-is (callers here always validate non-blank first) */
    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
