package com.travelplan.identity.controller;

import com.travelplan.identity.dto.ChangePasswordRequest;
import com.travelplan.identity.dto.CreateUserRequest;
import com.travelplan.identity.dto.UpdateEmailRequest;
import com.travelplan.identity.dto.UserResponse;
import com.travelplan.identity.entity.User;
import com.travelplan.identity.service.AuthService;
import com.travelplan.identity.service.PaymentServiceClient;
import com.travelplan.identity.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the user resource.
 *
 * No business logic here — data decisions are delegated to {@link UserService},
 * Bearer token validation to {@link AuthService} (the exact same manual
 * mechanism {@code GET /me} already uses — see {@link AuthController}, there
 * is no Spring Security filter chain in this codebase). Exception-to-HTTP
 * mapping is handled by {@link com.travelplan.identity.exception.GlobalExceptionHandler}.
 *
 * {@code POST /users} stays public so people can sign up, but creating an
 * ADMIN account through it requires an ADMIN token (the very first admin is
 * created at startup by {@code BootstrapAdminInitializer}, not through the
 * API). {@code GET /users}, {@code GET /users/{id}},
 * {@code DELETE /users/{id}} and {@code PATCH /users/{id}} all require the
 * caller to be an administrator ({@link AuthService#requireAdmin}, 403 if the
 * token is valid but lacks the {@code ADMIN} role claim, 401 if the token
 * itself is missing/invalid — see docs/sujet.md §4 on least privilege): the
 * list and the detail endpoint expose the same PII (email), and delete/update
 * are destructive or PII-modifying operations, so leaving either open while
 * protecting the list would just relocate the same vulnerability rather than
 * close it.
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final AuthService authService;
    private final PaymentServiceClient paymentServiceClient;

    public UserController(UserService userService, AuthService authService,
            PaymentServiceClient paymentServiceClient) {
        this.userService = userService;
        this.authService = authService;
        this.paymentServiceClient = paymentServiceClient;
    }

    /**
     * Create a new user. Public — see class-level note — but the role that
     * may be requested depends on the caller: without a valid ADMIN token
     * only {@code TRAVELER} (the default when {@code role} is omitted) and
     * {@code TRAVEL_MANAGER} are allowed; an authenticated ADMIN may create
     * any role. An absent/invalid/expired token is NOT a 401 here — the
     * caller is simply treated as anonymous ({@link AuthService#isAdmin}).
     *
     * @return 201 Created with the created user, 409 if email is already active,
     *         400 if the request body fails validation, 403 if ADMIN is
     *         requested without a valid ADMIN token
     */
    @PostMapping
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody CreateUserRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        UserResponse created = userService.create(request, authService.isAdmin(authorizationHeader));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Get an active user by id. Requires the caller to be an administrator — see class-level note.
     *
     * @return 200 with the user, 404 if absent or soft-deleted, 401 with a
     *         generic message if the Authorization header is missing/invalid/expired,
     *         403 if the token is valid but does not carry the ADMIN role
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getById(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        authService.requireAdmin(authorizationHeader);
        return ResponseEntity.ok(userService.findById(id));
    }

    /**
     * List all active users. Requires the caller to be an administrator — see class-level note.
     *
     * @return 200 with the list (empty list if none), 401 with a generic message if the
     *         Authorization header is missing/invalid/expired, 403 if the token
     *         is valid but does not carry the ADMIN role
     */
    @GetMapping
    public ResponseEntity<List<UserResponse>> getAll(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        authService.requireAdmin(authorizationHeader);
        return ResponseEntity.ok(userService.findAll());
    }

    /**
     * Soft-delete an active user. Requires the caller to be an administrator — see class-level note.
     *
     * <p>After the user is soft-deleted, cascades the deletion to that user's
     * payments in payment-service via {@link PaymentServiceClient}. This call
     * happens AFTER {@code userService.delete(id)} returns (its
     * {@code @Transactional} boundary has already committed) so the outbound
     * HTTP call never holds a DB transaction open. If the cascade call fails,
     * it is logged and swallowed — see {@link PaymentServiceClient} javadoc —
     * the user stays deleted regardless.</p>
     *
     * @return 204 No Content on success, 404 if absent or already soft-deleted,
     *         401 with a generic message if the Authorization header is
     *         missing/invalid/expired, 403 if the token is valid but does not
     *         carry the ADMIN role
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        authService.requireAdmin(authorizationHeader);
        userService.delete(id);
        paymentServiceClient.deleteAllPaymentsForUser(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update the email address of an active user. Requires the caller to be an
     * administrator — see class-level note. No other field can be changed through
     * this endpoint.
     *
     * @return 200 with the updated user, 404 if absent or soft-deleted, 409
     *         if the new email is already active on another user, 400 if the
     *         request body fails validation, 401 with a generic message if
     *         the Authorization header is missing/invalid/expired, 403 if the
     *         token is valid but does not carry the ADMIN role
     */
    @PatchMapping("/{id}")
    public ResponseEntity<UserResponse> updateEmail(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEmailRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        authService.requireAdmin(authorizationHeader);
        return ResponseEntity.ok(userService.updateEmail(id, request));
    }

    /**
     * Change the password of an active user (security audit G5). The caller
     * must be either the account's own owner or an administrator
     * ({@link AuthService#requireOwnerOrAdmin}): an owner must additionally
     * confirm their current password in the request body; an administrator
     * acting on someone else's account is not asked for one (see
     * {@link UserService#changePassword}).
     *
     * @return 200 with the updated user, 404 if absent or soft-deleted, 400
     *         if the request body fails validation (e.g. {@code newPassword}
     *         shorter than 8 characters), 401 with a generic message if the
     *         Authorization header is missing/invalid/expired, or (for an
     *         owner) if {@code currentPassword} is missing or incorrect, 403
     *         if the token is valid but the caller is neither the owner nor
     *         an administrator
     */
    @PatchMapping("/{id}/password")
    public ResponseEntity<UserResponse> changePassword(
            @PathVariable UUID id,
            @Valid @RequestBody ChangePasswordRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        User caller = authService.requireOwnerOrAdmin(authorizationHeader, id);
        boolean callerIsOwner = caller.getId().equals(id);
        return ResponseEntity.ok(userService.changePassword(id, request, callerIsOwner));
    }
}