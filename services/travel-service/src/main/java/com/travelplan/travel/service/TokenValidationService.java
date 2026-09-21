package com.travelplan.travel.service;

import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Bearer token validation for {@link com.travelplan.travel.controller.DestinationController}
 * and {@link com.travelplan.travel.controller.TransportController}.
 *
 * Mirrors the manual validation mechanism used by identity-service's own
 * {@code AuthService} (no Spring Security filter chain in this codebase
 * either), minus the final step: identity-service additionally looks up the
 * token's subject against its own user table, but travel-service has no
 * access to identity-service's database, so it stops at signature +
 * expiration validation plus a role check read straight from the claims.
 *
 * <p>Since docs/lets-travel-architecture-decisions.md §1, destinations are
 * not ADMIN-only any more: any of the three known roles may browse them
 * ({@link #requireAnyRole}), a subject requirement (Travelers must be able
 * to browse destinations/travels). Only mutation
 * (create/update/delete a destination or a transport link) stays restricted
 * to {@code ADMIN}/{@code TRAVEL_MANAGER} ({@link #requireManagerOrAdmin}) —
 * ordinary Travelers do not manage the catalogue.</p>
 *
 * <p>Since docs/lets-travel-architecture-decisions.md §2, a destination also
 * carries a {@code managerId} (it doubles as the subject's "Travel" entity),
 * so mutation is ownership-aware on top of the role check: a
 * {@code TRAVEL_MANAGER} may only create/update/delete their own travels,
 * {@code ADMIN} bypasses ownership entirely. {@link #requireManagerOrAdmin}
 * now returns the parsed {@link Claims} so callers can chain
 * {@link #requireOwnerOrAdmin(Claims, UUID)} — same two-step pattern as
 * payment-service's {@code TokenValidationService.requireOwnerOrAdmin}.</p>
 */
@Service
public class TokenValidationService {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_ROLE = "role";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_TRAVEL_MANAGER = "TRAVEL_MANAGER";
    private static final String ROLE_TRAVELER = "TRAVELER";
    private static final Set<String> KNOWN_ROLES = Set.of(ROLE_ADMIN, ROLE_TRAVEL_MANAGER, ROLE_TRAVELER);
    private static final Set<String> MANAGER_ROLES = Set.of(ROLE_ADMIN, ROLE_TRAVEL_MANAGER);

    /**
     * Subject of the service-to-service token payment-service mints (see its
     * {@code JwtService.generateServiceToken()}); accepted only by
     * {@link #requireServiceToken}.
     */
    private static final String SERVICE_PAYMENT_SUBJECT = "service:payment";

    private final JwtService jwtService;

    public TokenValidationService(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token that identity-service signed, that has not expired, and
     * that carries one of the three known roles. Reserved for read-only
     * endpoints — any authenticated, recognized-role caller may browse the
     * catalogue.
     *
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, or the token fails signature/expiration
     *         validation
     * @throws InsufficientRoleException if the token is otherwise valid but
     *         does not carry a recognized role claim
     */
    public void requireAnyRole(String authorizationHeader) {
        requireAnyRoleClaims(authorizationHeader);
    }

    /**
     * Same check as {@link #requireAnyRole}, but returns the parsed
     * {@link Claims} — needed by endpoints that are open to any known role
     * but still require the caller's own id (the JWT subject), e.g.
     * subscribing to a destination as oneself
     * (docs/lets-travel-architecture-decisions.md §3). Mirrors why
     * {@link #requireManagerOrAdmin} already returns {@link Claims}.
     *
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, or the token fails signature/expiration
     *         validation
     * @throws InsufficientRoleException if the token is otherwise valid but
     *         does not carry a recognized role claim
     */
    public Claims requireAnyRoleClaims(String authorizationHeader) {
        Claims claims = validateAndParse(authorizationHeader);
        String role = claims.get(CLAIM_ROLE, String.class);
        // Set.of(...).contains(null) throws NPE (immutable sets reject null
        // elements) rather than returning false — guard explicitly, a token
        // with no role claim at all must be a 403, not a 500.
        if (role == null || !KNOWN_ROLES.contains(role)) {
            throw new InsufficientRoleException();
        }
        return claims;
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token that identity-service signed, that has not expired, and
     * that carries the {@code ADMIN} or {@code TRAVEL_MANAGER} role.
     * Reserved for mutating endpoints (create/update/delete).
     *
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, or the token fails signature/expiration
     *         validation
     * @throws InsufficientRoleException if the token is otherwise valid but
     *         does not carry the {@code ADMIN} or {@code TRAVEL_MANAGER} role
     */
    public Claims requireManagerOrAdmin(String authorizationHeader) {
        Claims claims = validateAndParse(authorizationHeader);
        String role = claims.get(CLAIM_ROLE, String.class);
        if (role == null || !MANAGER_ROLES.contains(role)) {
            throw new InsufficientRoleException("Administrator or Travel Manager role required");
        }
        return claims;
    }

    /**
     * Reject unless {@code claims} carries the {@code ADMIN} role (implicit
     * full oversight, docs/lets-travel-architecture-decisions.md §1) or its
     * subject is exactly {@code resourceManagerId} — the ownership half of
     * RBAC that {@link #requireManagerOrAdmin} alone cannot express, since a
     * travel's manager is data, not a token claim. Mirrors payment-service's
     * {@code TokenValidationService.requireOwnerOrAdmin}.
     *
     * @throws InsufficientRoleException if the caller is neither the owning manager nor an admin
     */
    public void requireOwnerOrAdmin(Claims claims, UUID resourceManagerId) {
        if (isAdmin(claims)) {
            return;
        }
        if (resourceManagerId == null || !claims.getSubject().equals(resourceManagerId.toString())) {
            throw new InsufficientRoleException("Not allowed to manage another manager's travel");
        }
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token signed with the shared secret whose subject is exactly
     * {@code service:payment} — the service-to-service token payment-service
     * mints to report a subscription payment's outcome
     * (docs/lets-travel-architecture-decisions.md §4 addendum). Reserved for
     * {@code POST /internal/subscriptions/{ref}/payment-result}: a user token
     * (whatever its role, ADMIN included) is refused here, and conversely the
     * service token carries no role so every user-facing endpoint refuses it —
     * a traveler can never activate their own subscription by calling this.
     *
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, or the token fails signature/expiration validation
     * @throws InsufficientRoleException if the token is valid but is not the payment-service token
     */
    public void requireServiceToken(String authorizationHeader) {
        Claims claims = validateAndParse(authorizationHeader);
        if (!SERVICE_PAYMENT_SUBJECT.equals(claims.getSubject())) {
            throw new InsufficientRoleException("Service token required");
        }
    }

    public boolean isAdmin(Claims claims) {
        return ROLE_ADMIN.equals(claims.get(CLAIM_ROLE, String.class));
    }

    /** The caller's user id — the JWT subject, parsed once callers have already validated the token. */
    public UUID callerId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    private Claims validateAndParse(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new InvalidTokenException();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length());
        try {
            return jwtService.validate(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException();
        }
    }
}
