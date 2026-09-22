package com.travelplan.travel.service;

import com.travelplan.travel.dto.CreateTransportRequest;
import com.travelplan.travel.dto.RouteHop;
import com.travelplan.travel.dto.RouteResponse;
import com.travelplan.travel.dto.TransportResponse;
import com.travelplan.travel.dto.UpdateTransportRequest;
import com.travelplan.travel.entity.Destination;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.exception.InvalidTransportRequestException;
import com.travelplan.travel.exception.TransportNotFoundException;
import com.travelplan.travel.repository.DestinationRepository;
import com.travelplan.travel.repository.TransportEdge;
import com.travelplan.travel.repository.TransportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for the {@code TRANSPORT} relationship between two
 * destinations.
 *
 * Increment 2 scope: create a directed, single-hop transport link and list
 * the destinations reachable in exactly one hop, no anti-duplicate
 * protection — see {@link TransportRepository} for the rationale of the
 * last point. Extended by docs/lets-travel-architecture-decisions.md §11
 * (bonus) with multi-hop pathfinding and the rest of the relationship's CRUD
 * ({@code update}/{@code delete}).
 */
@Service
@Transactional(readOnly = true)
public class TransportService {

    private static final Set<String> ALLOWED_MODES = Set.of("TRAIN", "PLANE", "BUS", "CAR", "BOAT");

    /** Default/ceiling for {@code maxHops} on {@link #findRoute} — see the ADR §11 rationale. */
    private static final int DEFAULT_MAX_HOPS = 4;
    private static final int MIN_MAX_HOPS = 1;
    private static final int MAX_MAX_HOPS = 6;

    private final DestinationRepository destinationRepository;
    private final TransportRepository transportRepository;

    public TransportService(DestinationRepository destinationRepository, TransportRepository transportRepository) {
        this.destinationRepository = destinationRepository;
        this.transportRepository = transportRepository;
    }

    /**
     * Create a directed {@code TRANSPORT} relationship from {@code fromId} to
     * {@code request.getToDestinationId()}.
     *
     * Request-shape/business rules are checked before any database lookup,
     * so a self-loop, invalid mode, or non-positive duration is rejected
     * with 400 even if neither destination exists.
     *
     * @throws InvalidTransportRequestException if fromId equals toDestinationId,
     *         mode is not one of the five allowed values, or durationMinutes is not positive
     * <p>Ownership (security audit G1, ADR "Transports" addendum): the
     * {@code TRANSPORT} edge belongs to its <em>origin</em> destination, so a
     * non-admin caller must be that destination's {@code managerId}. The
     * target only has to exist and be active — linking towards someone else's
     * destination changes nothing on that node, and is checked <em>after</em>
     * ownership so a non-owner cannot probe which target ids exist.</p>
     *
     * @throws DestinationNotFoundException if the origin or the target does not
     *         exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         origin's {@code managerId} is not {@code callerId}
     */
    @Transactional
    public TransportResponse create(UUID fromId, CreateTransportRequest request, UUID callerId, boolean isAdmin) {
        UUID toId = request.getToDestinationId();

        if (fromId.equals(toId)) {
            throw InvalidTransportRequestException.selfLoop(fromId);
        }
        if (!ALLOWED_MODES.contains(request.getMode())) {
            throw InvalidTransportRequestException.invalidMode(request.getMode(), ALLOWED_MODES);
        }
        if (request.getDurationMinutes() <= 0) {
            throw InvalidTransportRequestException.invalidDuration(request.getDurationMinutes());
        }

        Destination origin = destinationRepository.findActiveById(fromId)
                .orElseThrow(() -> new DestinationNotFoundException(fromId));
        requireOwnership(origin, callerId, isAdmin);
        Destination target = destinationRepository.findActiveById(toId)
                .orElseThrow(() -> new DestinationNotFoundException(toId));

        UUID id = UUID.randomUUID();
        transportRepository.create(id, origin.getId(), target.getId(), request.getMode(), request.getDurationMinutes(),
                request.getDepartureTime(), request.getArrivalTime());

        return new TransportResponse(id, request.getMode(), request.getDurationMinutes(),
                request.getDepartureTime(), request.getArrivalTime(),
                target.getId(), target.getName(), target.getCountry());
    }

    /**
     * List destinations reachable from {@code id} via exactly one outgoing
     * {@code TRANSPORT} relationship. Filters {@code deletedAt IS NULL} on
     * both the origin (existence check below) and the target (inside the
     * repository query): a soft-deleted target disappears from this list
     * even though its relationship still exists in the graph.
     *
     * @throws DestinationNotFoundException if {@code id} does not exist or is soft-deleted
     */
    public List<TransportResponse> findOutgoing(UUID id) {
        destinationRepository.findActiveById(id)
                .orElseThrow(() -> new DestinationNotFoundException(id));

        return transportRepository.findActiveOutgoing(id).stream()
                .map(this::toTransportResponse)
                .collect(Collectors.toList());
    }

    /**
     * Update the mutable attributes ({@code mode}, {@code durationMinutes},
     * {@code departureTime}, {@code arrivalTime}) of the {@code TRANSPORT}
     * relationship {@code transportId}, which must start from {@code fromId}.
     * Never touches the origin or the target of the link — see
     * {@link UpdateTransportRequest}. Same validation rules as
     * {@link #create}, same ownership rule (origin's {@code managerId}), and
     * the same validation order: request shape, then origin existence, then
     * ownership, then the relationship's own existence — so a non-owner
     * cannot use a 404-vs-403 response to probe whether a given
     * {@code transportId} exists.
     *
     * @throws InvalidTransportRequestException if mode is not one of the five
     *         allowed values, or durationMinutes is not positive
     * @throws DestinationNotFoundException if {@code fromId} does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         origin's {@code managerId} is not {@code callerId}
     * @throws TransportNotFoundException if no {@code TRANSPORT} relationship
     *         with id {@code transportId} starts from {@code fromId}
     */
    @Transactional
    public TransportResponse update(UUID fromId, UUID transportId, UpdateTransportRequest request,
                                     UUID callerId, boolean isAdmin) {
        if (!ALLOWED_MODES.contains(request.getMode())) {
            throw InvalidTransportRequestException.invalidMode(request.getMode(), ALLOWED_MODES);
        }
        if (request.getDurationMinutes() <= 0) {
            throw InvalidTransportRequestException.invalidDuration(request.getDurationMinutes());
        }

        Destination origin = destinationRepository.findActiveById(fromId)
                .orElseThrow(() -> new DestinationNotFoundException(fromId));
        requireOwnership(origin, callerId, isAdmin);

        TransportEdge updated = transportRepository.update(fromId, transportId, request.getMode(),
                        request.getDurationMinutes(), request.getDepartureTime(), request.getArrivalTime())
                .orElseThrow(() -> new TransportNotFoundException(transportId));

        return toTransportResponse(updated);
    }

    /**
     * Physically delete the {@code TRANSPORT} relationship {@code transportId},
     * which must start from {@code fromId} — a real relationship, no
     * soft-delete state (ADR §11 addendum). Same ownership rule and
     * validation order as {@link #update} (minus the request-shape step,
     * since there is no body).
     *
     * @throws DestinationNotFoundException if {@code fromId} does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         origin's {@code managerId} is not {@code callerId}
     * @throws TransportNotFoundException if no {@code TRANSPORT} relationship
     *         with id {@code transportId} starts from {@code fromId}
     */
    @Transactional
    public void delete(UUID fromId, UUID transportId, UUID callerId, boolean isAdmin) {
        Destination origin = destinationRepository.findActiveById(fromId)
                .orElseThrow(() -> new DestinationNotFoundException(fromId));
        requireOwnership(origin, callerId, isAdmin);

        if (!transportRepository.delete(fromId, transportId)) {
            throw new TransportNotFoundException(transportId);
        }
    }

    /**
     * Shortest itinerary (in number of hops, not duration — see ADR §11 "Ce
     * que je sacrifie") from {@code fromId} to {@code toId} via outgoing
     * {@code TRANSPORT} relationships, at most {@code maxHops} hops long
     * (default 4, must be in [1, 6] otherwise — see
     * {@link InvalidTransportRequestException#invalidMaxHops}). Open read,
     * same access level as {@link #findOutgoing}.
     *
     * <p>The absence of a path is not the absence of a resource: once both
     * endpoints are confirmed to exist, a caller always gets 200 back, with
     * {@code reachable: false} when nothing connects them within
     * {@code maxHops}.</p>
     *
     * @throws InvalidTransportRequestException if {@code requestedMaxHops} is
     *         non-null and outside [1, 6]
     * @throws DestinationNotFoundException if {@code fromId} or {@code toId}
     *         does not exist or is soft-deleted
     */
    public RouteResponse findRoute(UUID fromId, UUID toId, Integer requestedMaxHops) {
        int maxHops = requestedMaxHops == null ? DEFAULT_MAX_HOPS : requestedMaxHops;
        if (maxHops < MIN_MAX_HOPS || maxHops > MAX_MAX_HOPS) {
            throw InvalidTransportRequestException.invalidMaxHops(maxHops);
        }

        destinationRepository.findActiveById(fromId).orElseThrow(() -> new DestinationNotFoundException(fromId));
        destinationRepository.findActiveById(toId).orElseThrow(() -> new DestinationNotFoundException(toId));

        return transportRepository.findShortestPath(fromId, toId, maxHops)
                .map(this::toRouteResponse)
                .orElseGet(RouteResponse::unreachable);
    }

    /**
     * Enforces the ownership half of RBAC on the {@code TRANSPORT}
     * relationship's <em>origin</em> destination (security audit G1, ADR
     * "Transports" addendum): an {@code ADMIN} caller always passes, a
     * {@code TRAVEL_MANAGER} caller must own the origin. Mirrors
     * {@code DestinationService.requireOwnership}.
     */
    private void requireOwnership(Destination origin, UUID callerId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (origin.getManagerId() == null || !origin.getManagerId().equals(callerId)) {
            throw new InsufficientRoleException("Not allowed to manage another manager's travel");
        }
    }

    private TransportResponse toTransportResponse(TransportEdge edge) {
        return new TransportResponse(edge.id(), edge.mode(), edge.durationMinutes(),
                edge.departureTime(), edge.arrivalTime(),
                edge.targetId(), edge.targetName(), edge.targetCountry());
    }

    private RouteResponse toRouteResponse(List<TransportEdge> path) {
        List<RouteHop> hops = path.stream()
                .map(edge -> new RouteHop(edge.targetId(), edge.targetName(), edge.targetCountry(),
                        edge.mode(), edge.durationMinutes(), edge.departureTime(), edge.arrivalTime()))
                .collect(Collectors.toList());
        int totalDurationMinutes = path.stream().mapToInt(TransportEdge::durationMinutes).sum();
        return RouteResponse.of(hops, totalDurationMinutes);
    }
}
