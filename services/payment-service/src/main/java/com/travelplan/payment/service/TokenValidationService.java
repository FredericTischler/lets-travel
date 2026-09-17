package com.travelplan.payment.service;

import com.travelplan.payment.exception.InsufficientRoleException;
import com.travelplan.payment.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Bearer token validation for every controller in this service.
 *
 * Mirrors the manual validation mechanism used by identity-service's own
 * {@code AuthService} (no Spring Security filter chain in this codebase
 * either), minus the final step: identity-service additionally looks up the
 * token's subject against its own user table, but payment-service has no
 * access to identity-service's database, so it stops at signature +
 * expiration validation, plus role/ownership checks read straight from the
 * claims — see docs/lets-travel-architecture-decisions.md §1.
 *
 * <p>Unlike the single-role Phase 0 version, payment-service now serves
 * three roles: {@code ADMIN} (oversight, sees/acts on every payment),
 * {@code TRAVEL_MANAGER} and {@code TRAVELER} (self-service, see/act on
 * their own payments only). {@link #requireAnyRole} authenticates and
 * confirms a known role; {@link #requireOwnerOrAdmin} adds the ownership
 * check a plain role check cannot express. {@link #requireAdminRole} stays
 * reserved for {@code PATCH /payments/{id}/status}: letting a resource
 * owner force their own payment's status would be a financial-integrity
 * issue, not merely an ownership question.</p>
 */
@Service
public class TokenValidationService {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_ROLE = "role";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_TRAVEL_MANAGER = "TRAVEL_MANAGER";
    public static final String ROLE_TRAVELER = "TRAVELER";
    private static final Set<String> KNOWN_ROLES = Set.of(ROLE_ADMIN, ROLE_TRAVEL_MANAGER, ROLE_TRAVELER);

    /**
     * Subject identity-service mints for the one service-to-service token it
     * issues (see identity-service's {@code JwtService.generateServiceToken()}).
     * This subject is only ever accepted on {@code DELETE /payments/by-user/{userId}};
     * every other endpoint explicitly rejects it via {@link #requireAnyRole}.
     */
    private static final String SERVICE_IDENTITY_SUBJECT = "service:identity";

    private final JwtService jwtService;

    public TokenValidationService(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token that identity-service signed, that has not expired, that
     * is NOT the {@code service:identity} service-to-service token, and that
     * carries one of the three known roles. Returns the claims so the caller
     * can apply {@link #requireOwnerOrAdmin} next.
     *
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, the token fails signature/expiration
     *         validation, or the token is the service-to-service token
     * @throws InsufficientRoleException if the token is otherwise valid but
     *         does not carry a recognized role claim
     */
    public Claims requireAnyRole(String authorizationHeader) {
        Claims claims = validateAndParse(authorizationHeader);
        if (SERVICE_IDENTITY_SUBJECT.equals(claims.getSubject())) {
            throw new InvalidTokenException();
        }
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
     * Bearer token that identity-service signed and that carries the
     * {@code ADMIN} role. Reserved for the one endpoint where letting a
     * resource owner self-serve would be a financial-integrity issue rather
     * than a mere ownership question — see class javadoc.
     *
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, or the token fails signature/expiration
     *         validation
     * @throws InsufficientRoleException if the token is otherwise valid but
     *         does not carry the {@code ADMIN} role claim
     */
    public Claims requireAdminRole(String authorizationHeader) {
        Claims claims = validateAndParse(authorizationHeader);
        if (!ROLE_ADMIN.equals(claims.get(CLAIM_ROLE, String.class))) {
            throw new InsufficientRoleException();
        }
        return claims;
    }

    /**
     * Reject unless {@code claims} carries the {@code ADMIN} role (implicit
     * full access, see docs/lets-travel-architecture-decisions.md §1) or its
     * subject is exactly {@code resourceOwnerId} — the ownership half of
     * RBAC that {@link #requireAnyRole} alone cannot express, since a
     * payment's owner is data, not a token claim.
     *
     * @throws InsufficientRoleException if the caller is neither the owner nor an admin
     */
    public void requireOwnerOrAdmin(Claims claims, UUID resourceOwnerId) {
        if (isAdmin(claims)) {
            return;
        }
        if (!claims.getSubject().equals(resourceOwnerId.toString())) {
            throw new InsufficientRoleException("Not allowed to act on another user's payment");
        }
    }

    public boolean isAdmin(Claims claims) {
        return ROLE_ADMIN.equals(claims.get(CLAIM_ROLE, String.class));
    }

    /** The caller's user id — the JWT subject, parsed once callers have already validated the token. */
    public UUID callerId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token that identity-service signed and that has not expired —
     * no subject restriction, so this accepts either a normal user token or
     * the {@code service:identity} service-to-service token. Reserved for
     * {@code DELETE /payments/by-user/{userId}}, the sole endpoint
     * identity-service's cascade delete is allowed to call.
     *
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, or the token fails signature/expiration
     *         validation
     * @throws InsufficientRoleException if the token is a normal user token
     *         (not the service-to-service token) and does not carry the
     *         {@code ADMIN} role
     */
    public void requireUserOrServiceToken(String authorizationHeader) {
        Claims claims = validateAndParse(authorizationHeader);
        if (!SERVICE_IDENTITY_SUBJECT.equals(claims.getSubject()) && !isAdmin(claims)) {
            throw new InsufficientRoleException();
        }
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
