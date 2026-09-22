package com.travelplan.travel.controller;

import com.travelplan.travel.dto.CreateTransportRequest;
import com.travelplan.travel.dto.RouteResponse;
import com.travelplan.travel.dto.TransportResponse;
import com.travelplan.travel.dto.UpdateTransportRequest;
import com.travelplan.travel.service.TokenValidationService;
import com.travelplan.travel.service.TransportService;
import io.jsonwebtoken.Claims;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the {@code TRANSPORT} relationship between two
 * destinations.
 *
 * No business logic here — all decisions are delegated to
 * {@link TransportService}. Bearer token validation is delegated to
 * {@link TokenValidationService}, same mechanism as {@link DestinationController}.
 * Exception-to-HTTP mapping is handled by
 * {@link com.travelplan.travel.exception.GlobalExceptionHandler}.
 *
 * Increment 2 scope: create a single-hop directed transport link, and list
 * destinations reachable in exactly one hop. Since
 * docs/lets-travel-architecture-decisions.md §1, {@code create}/{@code update}/
 * {@code delete} (mutation) are restricted to {@code ADMIN}/
 * {@code TRAVEL_MANAGER} and {@code getOutgoing}/{@code findRoute} (read) are
 * open to any of the three known roles — same split as
 * {@link DestinationController}.
 *
 * <p>Extended by docs/lets-travel-architecture-decisions.md §11 (bonus) with
 * {@code findRoute} (multi-hop pathfinding) and the rest of the
 * relationship's CRUD ({@code update}/{@code delete}).</p>
 *
 * <p>{@code create} is also ownership-aware (security audit G1): the link hangs
 * off its origin destination, so a {@code TRAVEL_MANAGER} may only create it
 * from a destination whose {@code managerId} is their own id; {@code ADMIN}
 * bypasses ownership. The check lives in {@link TransportService}, which alone
 * loads the origin — same split as {@code DestinationController.update/delete}.</p>
 */
@RestController
@RequestMapping("/destinations")
public class TransportController {

    private final TransportService transportService;
    private final TokenValidationService tokenValidationService;

    public TransportController(TransportService transportService, TokenValidationService tokenValidationService) {
        this.transportService = transportService;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Create a directed transport link from {@code fromId} to the
     * destination given in the request body. Requires a valid Bearer token —
     * see class-level note.
     *
     * @return 201 Created with the created link, 400 on a request rule
     *         violation (self-loop, invalid mode, non-positive duration),
     *         404 if origin or target is absent/soft-deleted,
     *         401 with a generic message if the Authorization header is missing/invalid/expired,
     *         403 if the caller is a {@code TRAVEL_MANAGER} who does not own the origin
     *         destination (ownership is checked on the origin only — see
     *         {@link TransportService#create})
     */
    @PostMapping("/{fromId}/transports")
    public ResponseEntity<TransportResponse> create(
            @PathVariable UUID fromId,
            @Valid @RequestBody CreateTransportRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireManagerOrAdmin(authorizationHeader);
        TransportResponse created = transportService.create(
                fromId, request, tokenValidationService.callerId(claims), tokenValidationService.isAdmin(claims));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * List destinations reachable from {@code id} via one outgoing transport
     * hop. Requires a valid Bearer token — see class-level note.
     *
     * @return 200 with the list (empty list if none), 404 if {@code id} is
     *         absent/soft-deleted,
     *         401 with a generic message if the Authorization header is missing/invalid/expired
     */
    @GetMapping("/{id}/transports")
    public ResponseEntity<List<TransportResponse>> getOutgoing(
            @PathVariable UUID id,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireAnyRole(authorizationHeader);
        return ResponseEntity.ok(transportService.findOutgoing(id));
    }

    /**
     * Update the mutable attributes ({@code mode}, {@code durationMinutes},
     * {@code departureTime}, {@code arrivalTime}) of a {@code TRANSPORT}
     * relationship — never its origin or target. Requires a valid Bearer
     * token — see class-level note.
     *
     * @return 200 with the updated link, 400 on a request rule violation
     *         (invalid mode, non-positive duration), 404 if {@code fromId} is
     *         absent/soft-deleted or no transport with {@code transportId}
     *         starts from it, 401 with a generic message if the
     *         Authorization header is missing/invalid/expired, 403 if the
     *         caller is a {@code TRAVEL_MANAGER} who does not own {@code fromId}
     */
    @PatchMapping("/{fromId}/transports/{transportId}")
    public ResponseEntity<TransportResponse> update(
            @PathVariable UUID fromId,
            @PathVariable UUID transportId,
            @Valid @RequestBody UpdateTransportRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireManagerOrAdmin(authorizationHeader);
        TransportResponse updated = transportService.update(
                fromId, transportId, request, tokenValidationService.callerId(claims), tokenValidationService.isAdmin(claims));
        return ResponseEntity.ok(updated);
    }

    /**
     * Physically delete a {@code TRANSPORT} relationship (not a soft-delete —
     * see docs/lets-travel-architecture-decisions.md §11 addendum). Requires
     * a valid Bearer token — see class-level note.
     *
     * @return 204 No Content on success, 404 if {@code fromId} is
     *         absent/soft-deleted or no transport with {@code transportId}
     *         starts from it, 401 with a generic message if the
     *         Authorization header is missing/invalid/expired, 403 if the
     *         caller is a {@code TRAVEL_MANAGER} who does not own {@code fromId}
     */
    @DeleteMapping("/{fromId}/transports/{transportId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID fromId,
            @PathVariable UUID transportId,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        Claims claims = tokenValidationService.requireManagerOrAdmin(authorizationHeader);
        transportService.delete(fromId, transportId, tokenValidationService.callerId(claims), tokenValidationService.isAdmin(claims));
        return ResponseEntity.noContent().build();
    }

    /**
     * Shortest multi-destination itinerary (in number of hops) from
     * {@code fromId} to {@code toId} via outgoing {@code TRANSPORT}
     * relationships (docs/lets-travel-architecture-decisions.md §11, bonus).
     * Requires a valid Bearer token — see class-level note.
     *
     * @param maxHops optional, default 4, must be between 1 and 6 inclusive
     * @return 200 with the route ({@code reachable: false} if no path exists
     *         within {@code maxHops} — not a 404, both destinations do
     *         exist), 400 if {@code maxHops} is outside [1, 6], 404 if
     *         {@code fromId} or {@code toId} is absent/soft-deleted, 401 with
     *         a generic message if the Authorization header is
     *         missing/invalid/expired
     */
    @GetMapping("/{fromId}/routes/{toId}")
    public ResponseEntity<RouteResponse> findRoute(
            @PathVariable UUID fromId,
            @PathVariable UUID toId,
            @RequestParam(name = "maxHops", required = false) Integer maxHops,
            @RequestHeader(name = "Authorization", required = false) String authorizationHeader) {
        tokenValidationService.requireAnyRole(authorizationHeader);
        return ResponseEntity.ok(transportService.findRoute(fromId, toId, maxHops));
    }
}