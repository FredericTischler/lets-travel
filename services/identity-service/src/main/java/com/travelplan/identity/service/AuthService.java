package com.travelplan.identity.service;

import com.travelplan.identity.dto.LoginRequest;
import com.travelplan.identity.dto.LoginResponse;
import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.exception.InsufficientRoleException;
import com.travelplan.identity.exception.InvalidCredentialsException;
import com.travelplan.identity.exception.InvalidTokenException;
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

    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder,
                        JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-safety");
    }

    /**
     * Verify email + password against active users.
     *
     * @throws InvalidCredentialsException if the email is unknown/soft-deleted
     *         or the password does not match — identical exception in both cases
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.getEmail()).orElse(null);
        String hashToCheck = (user != null) ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user);
        return new LoginResponse(user.getId(), user.getEmail(), token);
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