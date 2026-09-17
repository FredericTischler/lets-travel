package com.travelplan.travel.service;

import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;

/**
 * Bearer token validation for {@link com.travelplan.travel.controller.DestinationController}
 * and {@link com.travelplan.travel.controller.TransportController}.
 *
 * Mirrors the manual validation mechanism used by identity-service's own
 * {@code AuthService} (no Spring Security filter chain in this codebase
 * either), minus the final step: identity-service additionally looks up the
 * token's subject against its own user table, but travel-service has no
 * access to identity-service's database, so it stops at signature +
 * expiration validation, plus (new) an explicit role check: this service
 * only needs to know "is this a token identity-service really issued, is it
 * still valid, and does it carry the ADMIN role", not who the caller is —
 * least-privilege enforcement required by docs/sujet.md §4, on top of mere
 * token validity.
 */
@Service
public class TokenValidationService {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_ROLE = "role";
    private static final String ROLE_ADMIN = "ADMIN";

    private final JwtService jwtService;

    public TokenValidationService(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token that identity-service signed, that has not expired, and
     * that carries the {@code ADMIN} role claim.
     *
     * @param authorizationHeader raw header value, may be {@code null}
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, or the token fails signature/expiration
     *         validation
     * @throws InsufficientRoleException if the token is otherwise valid but
     *         does not carry the {@code ADMIN} role claim
     */
    public void requireValidToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new InvalidTokenException();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length());
        Claims claims;
        try {
            claims = jwtService.validate(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException();
        }
        if (!ROLE_ADMIN.equals(claims.get(CLAIM_ROLE, String.class))) {
            throw new InsufficientRoleException();
        }
    }
}