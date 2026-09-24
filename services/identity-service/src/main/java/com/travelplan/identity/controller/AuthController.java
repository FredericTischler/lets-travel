package com.travelplan.identity.controller;

import com.travelplan.identity.dto.LoginRequest;
import com.travelplan.identity.dto.LoginResponse;
import com.travelplan.identity.dto.RefreshRequest;
import com.travelplan.identity.dto.RefreshResponse;
import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.service.AuthService;
import com.travelplan.identity.service.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for authentication.
 *
 * No business logic here — credential verification, token issuance, and
 * token validation are all delegated to {@link AuthService}. Exception-to-HTTP
 * mapping is handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}.
 * Token validation on {@code GET /me} is manual (reading the header here,
 * verifying in the service) — there is no Spring Security filter chain in
 * this codebase. {@code /login} stays public (no token exists before a
 * successful login); {@link com.travelplan.identity.controller.UserController}
 * reuses this exact same manual-validation mechanism to protect the routes
 * that need it.
 */
@RestController
public class AuthController {

    private final AuthService authService;

    private final boolean trustForwardedFor;

    public AuthController(AuthService authService,
                          @Value("${login-throttle.trust-forwarded-for}") boolean trustForwardedFor) {
        this.authService = authService;
        this.trustForwardedFor = trustForwardedFor;
    }

    /**
     * Verify email + password for an active user.
     *
     * @return 200 with minimal identity (id, email) plus a short-lived JWT on
     *         success, 401 with a generic message on any failure (unknown
     *         email or wrong password produce the exact same response),
     *         429 with {@code Retry-After} once the email or client IP has
     *         too many recent failures (see {@code LoginThrottle})
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        String clientIp = ClientIp.resolve(
                httpRequest.getRemoteAddr(), httpRequest.getHeader("X-Forwarded-For"), trustForwardedFor);
        return ResponseEntity.ok(authService.login(request, clientIp));
    }

    /**
     * Exchange a refresh token (from a prior {@code POST /login} or
     * {@code POST /refresh}) for a fresh access token and a fresh refresh
     * token. The token in the request body is the sole credential — no
     * {@code Authorization} header is read or required.
     *
     * @return 200 with a fresh token pair, 400 if {@code refreshToken} is
     *         blank, 401 with a generic message if it is unknown, already
     *         used, expired, or its user is no longer active
     */
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    /**
     * Revoke a refresh token so it can no longer be redeemed. Always 204,
     * even for an unknown/already-revoked token (see
     * {@link AuthService#logout}) — logging out is not a way to probe which
     * tokens exist.
     *
     * @return 204 No Content, 400 if {@code refreshToken} is blank
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolve the active user identified by the {@code Authorization: Bearer
     * <token>} header.
     *
     * @return 200 with the user (id, email — never the password hash) if the
     *         token is valid and its subject is still an active user; 401
     *         with a generic message otherwise (header absent/malformed,
     *         token expired, signature invalid — all indistinguishable)
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        return ResponseEntity.ok(authService.getCurrentUser(authorizationHeader));
    }
}