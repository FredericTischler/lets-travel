package com.travelplan.identity;

import com.travelplan.identity.service.ClientIp;
import com.travelplan.identity.service.LoginThrottle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the sliding-window logic with a controllable clock (no Spring,
 * no database): window expiry, per-key independence, normalisation, reset on
 * success, memory bound. HTTP behaviour is in {@link LoginThrottleIntegrationTest}.
 */
class LoginThrottleTest {

    /** Mutable clock, advanced explicitly by the tests. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static LoginThrottle throttle(int emailMax, int ipMax, long windowSeconds, int maxKeys, Clock clock)
            throws Exception {
        Constructor<LoginThrottle> c = LoginThrottle.class.getDeclaredConstructor(
                int.class, int.class, long.class, int.class, Clock.class);
        c.setAccessible(true);
        return c.newInstance(emailMax, ipMax, windowSeconds, maxKeys, clock);
    }

    @Test
    void locksAnEmailAfterNFailuresAndReportsTheRemainingWait() throws Exception {
        TestClock clock = new TestClock();
        LoginThrottle t = throttle(3, 100, 600, 100, clock);

        for (int i = 0; i < 2; i++) {
            t.recordFailure("a@x.com", "1.1.1.1");
            assertThat(t.retryAfterSeconds("a@x.com", "1.1.1.1")).isEmpty();
        }
        t.recordFailure("a@x.com", "1.1.1.1");

        OptionalLong wait = t.retryAfterSeconds("a@x.com", "1.1.1.1");
        assertThat(wait).isPresent();
        assertThat(wait.getAsLong()).isEqualTo(600);
        clock.advance(Duration.ofSeconds(100));
        assertThat(t.retryAfterSeconds("a@x.com", "1.1.1.1").getAsLong()).isEqualTo(500);
    }

    @Test
    void theLockLiftsWhenTheOldestFailureLeavesTheWindow() throws Exception {
        TestClock clock = new TestClock();
        LoginThrottle t = throttle(2, 100, 600, 100, clock);
        t.recordFailure("a@x.com", "1.1.1.1");
        clock.advance(Duration.ofSeconds(300));
        t.recordFailure("a@x.com", "1.1.1.1");
        assertThat(t.retryAfterSeconds("a@x.com", "1.1.1.1")).isPresent();

        clock.advance(Duration.ofSeconds(299));   // first failure is 599 s old: still locked
        assertThat(t.retryAfterSeconds("a@x.com", "1.1.1.1")).isPresent();
        clock.advance(Duration.ofSeconds(1));     // 600 s: it leaves the window (sliding, not fixed)
        assertThat(t.retryAfterSeconds("a@x.com", "1.1.1.1")).isEmpty();
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() throws Exception {
        TestClock clock = new TestClock();
        LoginThrottle t = throttle(3, 100, 600, 100, clock);
        t.recordFailure("a@x.com", "1.1.1.1");
        t.recordFailure("a@x.com", "1.1.1.1");
        clock.advance(Duration.ofSeconds(601));
        t.recordFailure("a@x.com", "1.1.1.1");

        assertThat(t.retryAfterSeconds("a@x.com", "1.1.1.1")).isEmpty();
    }

    @Test
    void otherEmailsAreUnaffectedButTheSameIpCounterIsShared() throws Exception {
        LoginThrottle t = throttle(2, 4, 600, 100, new TestClock());
        t.recordFailure("a@x.com", "1.1.1.1");
        t.recordFailure("a@x.com", "1.1.1.1");

        assertThat(t.retryAfterSeconds("a@x.com", "9.9.9.9")).isPresent();
        assertThat(t.retryAfterSeconds("b@x.com", "9.9.9.9")).isEmpty();

        // A second email from the same IP fills the IP budget (4): now the IP is locked for everyone.
        t.recordFailure("b@x.com", "1.1.1.1");
        t.recordFailure("c@x.com", "1.1.1.1");
        assertThat(t.retryAfterSeconds("fresh@x.com", "1.1.1.1")).isPresent();
        assertThat(t.retryAfterSeconds("fresh@x.com", "2.2.2.2")).isEmpty();
    }

    @Test
    void emailsAreNormalisedSoCaseAndSpacesCannotBypassTheLock() throws Exception {
        LoginThrottle t = throttle(2, 100, 600, 100, new TestClock());
        t.recordFailure("Victim@Example.com", "1.1.1.1");
        t.recordFailure("  victim@example.COM ", "2.2.2.2");

        assertThat(t.retryAfterSeconds("VICTIM@example.com", "3.3.3.3")).isPresent();
    }

    @Test
    void aSuccessClearsTheEmailCounterOnly() throws Exception {
        LoginThrottle t = throttle(3, 100, 600, 100, new TestClock());
        t.recordFailure("a@x.com", "1.1.1.1");
        t.recordFailure("a@x.com", "1.1.1.1");
        t.recordSuccess("A@x.com");
        t.recordFailure("a@x.com", "1.1.1.1");
        t.recordFailure("a@x.com", "1.1.1.1");

        assertThat(t.retryAfterSeconds("a@x.com", "1.1.1.1")).isEmpty();   // 2 since reset, threshold 3
    }

    @Test
    void refusedAttemptsAreNotCountedSoALockNeverExtendsItself() throws Exception {
        TestClock clock = new TestClock();
        LoginThrottle t = throttle(2, 100, 600, 100, clock);
        t.recordFailure("a@x.com", "1.1.1.1");
        t.recordFailure("a@x.com", "1.1.1.1");
        // Callers must not record while locked (AuthService returns 429 first); polling is read-only.
        for (int i = 0; i < 50; i++) {
            t.retryAfterSeconds("a@x.com", "1.1.1.1");
        }
        clock.advance(Duration.ofSeconds(600));
        assertThat(t.retryAfterSeconds("a@x.com", "1.1.1.1")).isEmpty();
    }

    @Test
    void memoryIsBoundedByEvictingTheLeastRecentlyUpdatedKey() throws Exception {
        LoginThrottle t = throttle(1, 1000, 600, 3, new TestClock());
        for (int i = 0; i < 10; i++) {
            t.recordFailure("user" + i + "@x.com", "1.1.1.1");
        }
        // Only the 3 most recent emails are still tracked (and locked, threshold 1).
        assertThat(t.retryAfterSeconds("user9@x.com", "5.5.5.5")).isPresent();
        assertThat(t.retryAfterSeconds("user8@x.com", "5.5.5.5")).isPresent();
        assertThat(t.retryAfterSeconds("user7@x.com", "5.5.5.5")).isPresent();
        assertThat(t.retryAfterSeconds("user0@x.com", "5.5.5.5")).isEmpty();
    }

    @Test
    void nonPositiveSettingsAreRefusedAtStartup() {
        assertThatThrownBy(() -> new LoginThrottle(0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginThrottle(1, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clientIpIgnoresForwardedForUnlessTrustedAndThenTakesTheRightmostHop() {
        assertThat(ClientIp.resolve("10.0.0.1", "6.6.6.6", false)).isEqualTo("10.0.0.1");
        assertThat(ClientIp.resolve("10.0.0.1", "spoofed, 6.6.6.6", true)).isEqualTo("6.6.6.6");
        assertThat(ClientIp.resolve("10.0.0.1", null, true)).isEqualTo("10.0.0.1");
        assertThat(ClientIp.resolve("10.0.0.1", " ", true)).isEqualTo("10.0.0.1");
    }
}
