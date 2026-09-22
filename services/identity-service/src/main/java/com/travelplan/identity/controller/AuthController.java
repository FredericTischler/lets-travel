package com.travelplan.identity.controller;

import com.travelplan.identity.dto.LoginRequest;
import com.travelplan.identity.dto.LoginResponse;
import com.travelplan.identity.dto.RefreshResponse;
import com.travelplan.identity.dto.RefreshTokenRequest;
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
 *
 * <p>{@code POST /auth/refresh} and {@code POST /auth/logout} (security
 * audit G10) are public too: the refresh token itself, not a Bearer header,
 * is the credential they consume — see {@code RefreshTokenService}.</p>
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

    /**
     * Exchange a refresh token for a fresh access token + refresh token pair.
     * The presented refresh token is consumed (revoked) in the process — it
     * cannot be presented again, see {@code RefreshTokenService#rotate}.
     * Public: the refresh token itself is the credential, no Bearer header
     * is involved.
     *
     * @return 200 with the new pair, 401 with a generic message if the token
     *         is unknown, expired, already revoked, or its user has since
     *         been soft-deleted, 400 if the request body fails validation
     */
    @PostMapping("/auth/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    /**
     * Revoke a refresh token. Public, same reasoning as {@link #refresh}.
     *
     * @return 204 No Content always — whether the token existed, was already
     *         revoked, or is expired; no oracle on refresh-token existence.
     *         400 if the request body fails validation.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}