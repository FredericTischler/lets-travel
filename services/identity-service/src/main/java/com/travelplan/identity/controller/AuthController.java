package com.travelplan.identity.controller;

import com.travelplan.identity.dto.LoginRequest;
import com.travelplan.identity.dto.LoginResponse;
import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.service.AuthService;
import jakarta.validation.Valid;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Verify email + password for an active user.
     *
     * @return 200 with minimal identity (id, email) plus a short-lived JWT on
     *         success, 401 with a generic message on any failure (unknown
     *         email or wrong password produce the exact same response)
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
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