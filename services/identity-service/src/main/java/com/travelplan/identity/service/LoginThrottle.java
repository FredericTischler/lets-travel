package com.travelplan.identity.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Brute-force protection for {@code POST /login} (security audit G4,
 * docs/lets-travel-architecture-decisions.md, addendum "Limitation des
 * tentatives de login").
 *
 * <p><b>Rule.</b> Two independent sliding-window counters of <em>failed</em>
 * attempts: one per normalised email ({@code trim + lowercase}, so
 * {@code A@x.com} and {@code a@x.com} share a counter), one per client IP.
 * When a key has {@code max} failures inside the last {@code window}, further
 * attempts on that key are refused (429 + {@code Retry-After}) <em>before</em>
 * any database lookup or BCrypt comparison, until the oldest of those failures
 * leaves the window. Refused attempts are not counted, so the lock never
 * extends itself. A successful login clears the email counter (not the IP
 * one: a bot alternating a valid account with guesses elsewhere must not
 * reset its own budget).</p>
 *
 * <p><b>No account enumeration.</b> The email key is the raw, normalised
 * input: it is recorded and enforced identically whether or not the account
 * exists, and a locked key answers without touching the database — so a known
 * and an unknown email are indistinguishable (same 401s, same lock after the
 * same number of failures, same 429).</p>
 *
 * <p><b>Honest limits.</b> State is in memory and <em>per replica</em>: with
 * N replicas behind the load balancer an attacker gets up to N times the
 * budget, and a restart forgets everything (no shared store by design — no new
 * infrastructure). Memory is bounded ({@code maxTrackedKeys} per counter,
 * expired entries purged first, then least-recently-updated evicted), so an
 * attacker flooding random emails can evict entries, including a victim's.
 * A locked email also blocks the legitimate owner for at most one window
 * (targeted lockout). It complements, and does not replace, an edge rate
 * limiter.</p>
 */
@Component
public class LoginThrottle {

    private final int emailMaxFailures;
    private final int ipMaxFailures;
    private final long windowMillis;
    private final Clock clock;
    private final FailureWindow byEmail;
    private final FailureWindow byIp;

    @Autowired
    public LoginThrottle(
            @Value("${login-throttle.email-max-failures}") int emailMaxFailures,
            @Value("${login-throttle.ip-max-failures}") int ipMaxFailures,
            @Value("${login-throttle.window-seconds}") long windowSeconds,
            @Value("${login-throttle.max-tracked-keys}") int maxTrackedKeys) {
        this(emailMaxFailures, ipMaxFailures, windowSeconds, maxTrackedKeys, Clock.systemUTC());
    }

    LoginThrottle(int emailMaxFailures, int ipMaxFailures, long windowSeconds, int maxTrackedKeys, Clock clock) {
        if (emailMaxFailures < 1 || ipMaxFailures < 1 || windowSeconds < 1 || maxTrackedKeys < 1) {
            throw new IllegalArgumentException("login-throttle.* values must all be >= 1");
        }
        this.emailMaxFailures = emailMaxFailures;
        this.ipMaxFailures = ipMaxFailures;
        this.windowMillis = windowSeconds * 1000;
        this.clock = clock;
        this.byEmail = new FailureWindow(maxTrackedKeys);
        this.byIp = new FailureWindow(maxTrackedKeys);
    }

    /** Canonical form of an email for throttling purposes. */
    static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @return seconds to wait if this (email, ip) pair is currently locked out,
     *         empty if the attempt may proceed
     */
    public OptionalLong retryAfterSeconds(String email, String clientIp) {
        long now = clock.millis();
        long emailWait = byEmail.remainingMillis(normalise(email), emailMaxFailures, windowMillis, now);
        long ipWait = byIp.remainingMillis(clientIp, ipMaxFailures, windowMillis, now);
        long wait = Math.max(emailWait, ipWait);
        return wait > 0 ? OptionalLong.of(Math.max(1, (wait + 999) / 1000)) : OptionalLong.empty();
    }

    public void recordFailure(String email, String clientIp) {
        long now = clock.millis();
        byEmail.add(normalise(email), emailMaxFailures, windowMillis, now);
        byIp.add(clientIp, ipMaxFailures, windowMillis, now);
    }

    public void recordSuccess(String email) {
        byEmail.clear(normalise(email));
    }

    /**
     * Bounded map: key -> timestamps of the last {@code max} failures. Keeping
     * only the last {@code max} is enough for a sliding window: the key is
     * locked iff it holds {@code max} entries and the oldest is still inside
     * the window, and it unlocks when that oldest one expires.
     */
    private static final class FailureWindow {

        private final Map<String, Deque<Long>> entries;
        private final int maxKeys;

        FailureWindow(int maxKeys) {
            this.maxKeys = maxKeys;
            // access-order = least recently updated first, for eviction.
            this.entries = new LinkedHashMap<>(16, 0.75f, true);
        }

        synchronized long remainingMillis(String key, int max, long windowMillis, long now) {
            Deque<Long> failures = entries.get(key);
            if (failures == null) {
                return 0;
            }
            expire(failures, windowMillis, now);
            if (failures.isEmpty()) {
                entries.remove(key);
                return 0;
            }
            return failures.size() >= max ? failures.peekFirst() + windowMillis - now : 0;
        }

        synchronized void add(String key, int max, long windowMillis, long now) {
            Deque<Long> failures = entries.get(key);
            if (failures == null) {
                makeRoom(windowMillis, now);
                failures = new ArrayDeque<>();
                entries.put(key, failures);
            }
            expire(failures, windowMillis, now);
            failures.addLast(now);
            while (failures.size() > max) {
                failures.pollFirst();
            }
        }

        synchronized void clear(String key) {
            entries.remove(key);
        }

        private void makeRoom(long windowMillis, long now) {
            if (entries.size() < maxKeys) {
                return;
            }
            entries.values().removeIf(d -> {
                expire(d, windowMillis, now);
                return d.isEmpty();
            });
            while (entries.size() >= maxKeys) {
                String eldest = entries.keySet().iterator().next();
                entries.remove(eldest);
            }
        }

        private static void expire(Deque<Long> failures, long windowMillis, long now) {
            while (!failures.isEmpty() && failures.peekFirst() <= now - windowMillis) {
                failures.pollFirst();
            }
        }
    }
}
