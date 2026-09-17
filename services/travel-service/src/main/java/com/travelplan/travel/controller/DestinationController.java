package com.travelplan.travel.controller;

import com.travelplan.travel.dto.CreateDestinationRequest;
import com.travelplan.travel.dto.DestinationResponse;
import com.travelplan.travel.dto.UpdateDestinationRequest;
import com.travelplan.travel.service.DestinationService;
import com.travelplan.travel.service.TokenValidationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the destination resource.
 *
 * No business logic here — all decisions are delegated to {@link DestinationService}.
 * Bearer token validation is delegated to {@link TokenValidationService} (the
 * exact same manual mechanism identity-service uses — no Spring Security
 * filter chain in this codebase). Exception-to-HTTP mapping is handled by
 * {@link com.travelplan.travel.exception.GlobalExceptionHandler}.
 *
 * Every endpoint requires a valid Bearer token issued by identity-service.
 * All five methods are protected uniformly (including {@code getById} and
 * {@code update}, not just the subset the task explicitly named): leaving
 * read/update open while protecting create/delete would just relocate the
 * same vulnerability rather than close it — same reasoning identity-service
 * already applies to its own user resource.
 */
@RestController
@RequestMapping("/destinations")
public class DestinationController {

    private final DestinationService destinationService;
    private final TokenValidationService tokenValidationService;

    public DestinationController(DestinationService destinationService, TokenValidationService tokenValidationService) {
        this.destinationService = destinationService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Create a new destination. Requires a valid Bearer token — see class-level note.
     *
     * @return 201 Created with the created destination, 400 if the request body fails validation,
     *         401 with a generic message if the Authorization header is missing/invalid/expired
     */
    @PostMapping
    public ResponseEntity<DestinationResponse> create(
            @Valid @RequestBody CreateDestinationRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireValidToken(authorizationHeader);
        DestinationResponse created = destinationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Get an active destination by id. Requires a valid Bearer token — see class-level note.
     *
     * @return 200 with the destination, 404 if absent or soft-deleted,
     *         401 with a generic message if the Authorization header is missing/invalid/expired
     */
    @GetMapping("/{id}")
    public ResponseEntity<DestinationResponse> getById(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireValidToken(authorizationHeader);
        return ResponseEntity.ok(destinationService.findById(id));
    }

    /**
     * List all active destinations. Requires a valid Bearer token — see class-level note.
     *
     * @return 200 with the list (empty list if none),
     *         401 with a generic message if the Authorization header is missing/invalid/expired
     */
    @GetMapping
    public ResponseEntity<List<DestinationResponse>> getAll(
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireValidToken(authorizationHeader);
        return ResponseEntity.ok(destinationService.findAll());
    }

    /**
     * Replace the mutable fields of an active destination. Requires a valid
     * Bearer token — see class-level note.
     *
     * @return 200 with the updated destination, 400 if the request body fails validation,
     *         404 if absent or soft-deleted,
     *         401 with a generic message if the Authorization header is missing/invalid/expired
     */
    @PutMapping("/{id}")
    public ResponseEntity<DestinationResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDestinationRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireValidToken(authorizationHeader);
        return ResponseEntity.ok(destinationService.update(id, request));
    }

    /**
     * Soft-delete an active destination. Requires a valid Bearer token — see class-level note.
     *
     * @return 204 No Content on success, 404 if absent or already soft-deleted,
     *         401 with a generic message if the Authorization header is missing/invalid/expired
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireValidToken(authorizationHeader);
        destinationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}