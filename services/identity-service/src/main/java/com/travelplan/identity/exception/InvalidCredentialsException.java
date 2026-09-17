package com.travelplan.identity.exception;

/**
 * Thrown by {@link com.travelplan.identity.service.AuthService} whenever
 * {@code POST /login} credentials do not match an active user — whether the
 * email is unknown or the password is wrong. The message is intentionally
 * generic and identical in both cases (see {@link GlobalExceptionHandler}).
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}