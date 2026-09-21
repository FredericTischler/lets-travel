package com.travelplan.identity.exception;

/**
 * Thrown by {@link com.travelplan.identity.service.AuthService#login} when the
 * login throttle refuses an attempt (security audit G4). Mapped to 429 with a
 * {@code Retry-After} header by {@link GlobalExceptionHandler}. The message is
 * generic: it never says which key (email or IP) tripped, nor whether the
 * account exists.
 */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("Too many login attempts, try again later");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
