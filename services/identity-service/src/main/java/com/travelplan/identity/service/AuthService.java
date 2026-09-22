package com.travelplan.identity.service;

import com.travelplan.identity.dto.LoginRequest;
import com.travelplan.identity.dto.LoginResponse;
import com.travelplan.identity.dto.RefreshResponse;
import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.exception.InsufficientRoleException;
import com.travelplan.identity.exception.InvalidCredentialsException;
import com.travelplan.identity.exception.InvalidTokenException;
import com.travelplan.identity.exception.TooManyLoginAttemptsException;
import com.travelplan.identity.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Authentication logic for {@code POST /login} and {@code GET /me}.
 *
 * A successful login confirms that the given credentials match an active
 * (non-deleted) user and issues a short-lived JWT (see {@link JwtService}).
 * Unknown email and wrong password are deliberately indistinguishable to the
 * caller — same HTTP status, same message. {@code GET /me} applies the same
 * non-disclosure philosophy to token validation: every failure reason
 * (missing header, malformed token, expired, bad signature, user no longer
 * active) collapses to the same generic {@link InvalidTokenException}.
 *
 * <p>{@link #requireAdmin} is the least-privilege gate used by every
 * administrative route in {@code UserController}: unlike {@link #getCurrentUser}
 * (which only needs "is this token valid"), it additionally checks the
 * token's {@code role} claim ({@link JwtService#CLAIM_ROLE}) and rejects with
 * {@link InsufficientRoleException} (403) when it is not
 * {@link JwtService#ROLE_ADMIN} — authenticated but not authorized, per
 * docs/sujet.md §4.</p>
 *
 * <p>{@link #requireAnyRole} is the equivalent gate for endpoints any of the
 * 3 known roles may use (introduced for {@code ReportController} —
 * docs/lets-travel-architecture-decisions.md §5): same "authenticated AND
 * carries a recognized role" check as payment-service's
 * {@code TokenValidationService#requireAnyRole}, except this one can also
 * resolve the caller against this service's own {@code users} table (unlike
 * payment-service, which has no access to identity_db), so it returns the
 * resolved {@link User} rather than just the raw claims.</p>
 *
 * <p>{@link #requireOwnerOrAdmin} is adapted from payment-service's
 * {@code TokenValidationService#requireOwnerOrAdmin} for the endpoints that
 * need "the owner, or any admin" rather than a role check alone (introduced
 * for {@code PATCH /users/{id}/password} — security audit G5). Like
 * {@link #requireAnyRole} it returns the resolved {@link User}: the caller
 * needs to know whether that user IS {@code resourceOwnerId} (an id
 * comparison expresses this more directly here than a boolean would).</p>
 *
 * <p>{@link #login} additionally issues a refresh token (security audit
 * G10, {@link RefreshTokenService}); {@link #refresh} rotates one for a
 * fresh access/refresh pair, {@link #logout} revokes one. Refresh tokens are
 * opaque values, not JWTs — {@link JwtService} is not involved in validating
 * them.</p>
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    // Fixed dummy password, hashed once at construction time (i.e. once per
    // application startup, not per request). Used to run a BCrypt comparison
    // even when the email is unknown, so an unknown-email response takes the
    // same time as a wrong-password response — this prevents account
    // enumeration via response-time measurement.
    private final String dummyHash;

    private final LoginThrottle loginThrottle;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder,
                        JwtService jwtService, LoginThrottle loginThrottle,
                        RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginThrottle = loginThrottle;
        this.refreshTokenService = refreshTokenService;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-safety");
    }

    /**
     * Verify email + password against active users.
     *
     * <p>Brute-force protection ({@link LoginThrottle}): a locked email or IP is
     * refused before any lookup or BCrypt work; every failure — unknown email or
     * wrong password alike — is recorded, a success clears the email's counter.</p>
     *
     * <p>{@code request.getEmail()} is normalised ({@link EmailNormalizer},
     * security audit G12) before the throttle check and the lookup, so
     * {@code A@x.com} and {@code a@x.com} always hit the same throttle
     * counter and the same account — consistent with how the email was
     * normalised and stored by {@link UserService#create}.</p>
     *
     * @throws InvalidCredentialsException if the email is unknown/soft-deleted
     *         or the password does not match — identical exception in both cases
     * @throws TooManyLoginAttemptsException if the email or the client IP is
     *         currently locked out
     */
    public LoginResponse login(LoginRequest request, String clientIp) {
        String email = EmailNormalizer.normalize(request.getEmail());

        loginThrottle.retryAfterSeconds(email, clientIp).ifPresent(seconds -> {
            throw new TooManyLoginAttemptsException(seconds);
        });

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElse(null);
        String hashToCheck = (user != null) ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (user == null || !matches) {
            loginThrottle.recordFailure(email, clientIp);
            throw new InvalidCredentialsException();
        }
        loginThrottle.recordSuccess(email);

        String token = jwtService.generateToken(user);
        String refreshToken = refreshTokenService.issue(user.getId());
        return new LoginResponse(user.getId(), user.getEmail(), token, refreshToken);
    }

    /**
     * Resolve the active user identified by the Bearer token in the
     * {@code Authorization} header.
     *
     * @param authorizationHeader raw header value, may be {@code null}
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, the token fails signature/expiration
     *         validation, or its subject no longer maps to an active user
     */
    public UserResponse getCurrentUser(String authorizationHeader) {
        Claims claims = extractValidClaims(authorizationHeader);
        User user = resolveActiveUser(claims);
        return UserResponse.from(user);
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token that is valid, still active for a currently-active user
     * (same checks as {@link #getCurrentUser}), AND whose {@code role} claim
     * is {@link JwtService#ROLE_ADMIN}.
     *
     * @param authorizationHeader raw header value, may be {@code null}
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, the token fails signature/expiration
     *         validation, or its subject no longer maps to an active user
     * @throws InsufficientRoleException if the token is otherwise valid but
     *         does not carry the {@code ADMIN} role claim
     */
    public void requireAdmin(String authorizationHeader) {
        Claims claims = extractValidClaims(authorizationHeader);
        resolveActiveUser(claims);
        if (!JwtService.ROLE_ADMIN.equals(claims.get(JwtService.CLAIM_ROLE, String.class))) {
            throw new InsufficientRoleException();
        }
    }

    /**
     * Non-throwing variant of {@link #requireAdmin}, for the one endpoint that
     * is public but changes behaviour for an administrator
     * ({@code POST /users}: only an ADMIN may create an ADMIN account).
     *
     * <p>Returns {@code false} — never throws — for an absent, malformed,
     * expired or forged token, for a token whose user is no longer active,
     * and for a valid token that is not ADMIN: all of those callers are simply
     * treated as anonymous, they do not get a 401 on a public endpoint.</p>
     *
     * @param authorizationHeader raw header value, may be {@code null}
     */
    public boolean isAdmin(String authorizationHeader) {
        try {
            requireAdmin(authorizationHeader);
            return true;
        } catch (InvalidTokenException | InsufficientRoleException ex) {
            return false;
        }
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token that is valid, still active for a currently-active user
     * (same checks as {@link #getCurrentUser}), AND whose {@code role} claim
     * is one of the 3 known roles ({@link JwtService#KNOWN_ROLES}).
     *
     * @param authorizationHeader raw header value, may be {@code null}
     * @return the resolved active {@link User} (the caller) — callers read
     *         its id to set ownership fields server-side (e.g.
     *         {@code reporterId} on {@code POST /reports}), never trusting a
     *         client-supplied value for that purpose
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, the token fails signature/expiration
     *         validation, or its subject no longer maps to an active user
     * @throws InsufficientRoleException if the token is otherwise valid but
     *         does not carry a recognized role claim
     */
    public User requireAnyRole(String authorizationHeader) {
        Claims claims = extractValidClaims(authorizationHeader);
        User user = resolveActiveUser(claims);
        String role = claims.get(JwtService.CLAIM_ROLE, String.class);
        // Set.of(...).contains(null) throws NPE (immutable sets reject null
        // elements) rather than returning false — guard explicitly, a token
        // with no role claim at all must be a 403, not a 500.
        if (role == null || !JwtService.KNOWN_ROLES.contains(role)) {
            throw new InsufficientRoleException();
        }
        return user;
    }

    /**
     * Reject the request unless the {@code Authorization} header carries a
     * Bearer token that is valid, still active for a currently-active user
     * (same checks as {@link #getCurrentUser}), AND the caller is either
     * {@code resourceOwnerId} or an {@code ADMIN}.
     *
     * @param authorizationHeader raw header value, may be {@code null}
     * @param resourceOwnerId the id of the account the action targets
     * @return the resolved active {@link User} (the caller) — compare its id
     *         to {@code resourceOwnerId} to know whether the caller IS the
     *         owner, which callers need for finer-grained rules a plain
     *         owner-or-admin gate cannot express by itself (e.g.
     *         {@code UserController#changePassword}: an owner must confirm
     *         their current password, an admin acting on someone else's
     *         account must not)
     * @throws InvalidTokenException if the header is absent, not a
     *         {@code Bearer} value, the token fails signature/expiration
     *         validation, or its subject no longer maps to an active user
     * @throws InsufficientRoleException if the caller is neither the owner nor an admin
     */
    public User requireOwnerOrAdmin(String authorizationHeader, UUID resourceOwnerId) {
        Claims claims = extractValidClaims(authorizationHeader);
        User user = resolveActiveUser(claims);
        boolean isAdmin = JwtService.ROLE_ADMIN.equals(claims.get(JwtService.CLAIM_ROLE, String.class));
        if (!isAdmin && !user.getId().equals(resourceOwnerId)) {
            throw new InsufficientRoleException();
        }
        return user;
    }

    /**
     * Rotate a refresh token: consumes {@code refreshToken} (it cannot be
     * presented again, see {@link RefreshTokenService#rotate}) and issues a
     * fresh access token + refresh token pair for the user it was issued to.
     *
     * @throws InvalidTokenException if the token is unknown, expired, already
     *         revoked, or its user has since been soft-deleted — all
     *         indistinguishable to the caller, same generic 401 in every case
     */
    public RefreshResponse refresh(String refreshToken) {
        UUID userId = refreshTokenService.rotate(refreshToken);
        User user = userRepository.findActiveById(userId).orElseThrow(InvalidTokenException::new);
        String accessToken = jwtService.generateToken(user);
        String newRefreshToken = refreshTokenService.issue(user.getId());
        return new RefreshResponse(accessToken, newRefreshToken);
    }

    /**
     * Revoke {@code refreshToken} if a matching, not-already-revoked row
     * exists. Never throws: {@code POST /auth/logout} always answers 204,
     * whether the token existed, was already revoked, or is expired — no
     * oracle on refresh-token existence.
     */
    public void logout(String refreshToken) {
        refreshTokenService.revokeIfPresent(refreshToken);
    }

    private Claims extractValidClaims(String authorizationHeader) {
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

    private User resolveActiveUser(Claims claims) {
        UUID userId;
        try {
            userId = UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException ex) {
            throw new InvalidTokenException();
        }
        return userRepository.findActiveById(userId)
                .orElseThrow(InvalidTokenException::new);
    }
}