package com.travelplan.travel.service;

import com.travelplan.travel.dto.CreateTransportRequest;
import com.travelplan.travel.dto.RouteResponse;
import com.travelplan.travel.dto.TransportResponse;
import com.travelplan.travel.dto.UpdateTransportRequest;
import com.travelplan.travel.entity.Destination;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.exception.InvalidRouteRequestException;
import com.travelplan.travel.exception.InvalidTransportRequestException;
import com.travelplan.travel.exception.RouteNotFoundException;
import com.travelplan.travel.exception.TransportNotFoundException;
import com.travelplan.travel.repository.DestinationRepository;
import com.travelplan.travel.repository.TransportEdge;
import com.travelplan.travel.repository.TransportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for the {@code TRANSPORT} relationship between two
 * destinations: create/update/delete a directed link, list the destinations
 * reachable in one hop, and find the fewest-hops chain of links between two
 * destinations.
 */
@Service
@Transactional(readOnly = true)
public class TransportService {

    private static final Set<String> ALLOWED_MODES = Set.of("TRAIN", "PLANE", "BUS", "CAR", "BOAT");

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
        validateModeAndDuration(request.getMode(), request.getDurationMinutes());

        Destination origin = requireOwnedActiveOrigin(fromId, callerId, isAdmin);
        Destination target = destinationRepository.findActiveById(toId)
                .orElseThrow(() -> new DestinationNotFoundException(toId));

        UUID transportId = transportRepository.create(origin.getId(), target.getId(), request.getMode(),
                request.getDurationMinutes(), request.getDepartureTime(), request.getArrivalTime());

        return new TransportResponse(transportId, request.getMode(), request.getDurationMinutes(),
                request.getDepartureTime(), request.getArrivalTime(),
                new TransportResponse.Target(target.getId(), target.getName(), target.getCountry()));
    }

    /**
     * List destinations reachable from {@code id} via exactly one outgoing
     * {@code TRANSPORT} relationship. Filters {@code deletedAt IS NULL} on
     * the origin (existence check below), the transport, and the target
     * (inside the repository query): a soft-deleted target or transport
     * disappears from this list even though its relationship still exists
     * in the graph.
     *
     * @throws DestinationNotFoundException if {@code id} does not exist or is soft-deleted
     */
    public List<TransportResponse> findOutgoing(UUID id) {
        destinationRepository.findActiveById(id)
                .orElseThrow(() -> new DestinationNotFoundException(id));

        return transportRepository.findActiveOutgoing(id).stream().map(this::toResponse).toList();
    }

    /**
     * Replace the mutable fields (mode, durationMinutes, departure/arrival
     * time) of one active transport hanging off {@code fromId}. Same
     * ownership rule as {@link #create}: the caller must own {@code fromId}
     * (or be admin).
     *
     * @throws InvalidTransportRequestException if mode is not one of the five
     *         allowed values, or durationMinutes is not positive
     * @throws DestinationNotFoundException if {@code fromId} does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         origin's {@code managerId} is not {@code callerId}
     * @throws TransportNotFoundException if no active transport with
     *         {@code transportId} hangs off {@code fromId}
     */
    @Transactional
    public TransportResponse update(UUID fromId, UUID transportId, UpdateTransportRequest request,
                                     UUID callerId, boolean isAdmin) {
        validateModeAndDuration(request.getMode(), request.getDurationMinutes());
        requireOwnedActiveOrigin(fromId, callerId, isAdmin);

        TransportEdge updated = transportRepository.update(fromId, transportId, request.getMode(),
                        request.getDurationMinutes(), request.getDepartureTime(), request.getArrivalTime())
                .orElseThrow(() -> new TransportNotFoundException(fromId, transportId));

        return toResponse(updated);
    }

    /**
     * Soft-delete one active transport hanging off {@code fromId}. Same
     * ownership rule as {@link #create}.
     *
     * @throws DestinationNotFoundException if {@code fromId} does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         origin's {@code managerId} is not {@code callerId}
     * @throws TransportNotFoundException if no active transport with
     *         {@code transportId} hangs off {@code fromId} (absent, or already deleted)
     */
    @Transactional
    public void delete(UUID fromId, UUID transportId, UUID callerId, boolean isAdmin) {
        requireOwnedActiveOrigin(fromId, callerId, isAdmin);

        boolean deleted = transportRepository.softDelete(fromId, transportId, OffsetDateTime.now(ZoneOffset.UTC));
        if (!deleted) {
            throw new TransportNotFoundException(fromId, transportId);
        }
    }

    /**
     * The fewest-hops chain of active {@code TRANSPORT} edges from
     * {@code fromId} to {@code toId} (open to any of the three known roles,
     * read-only — same split as {@link #findOutgoing}, no ownership check).
     *
     * @throws InvalidRouteRequestException if {@code fromId} equals {@code toId}
     * @throws DestinationNotFoundException if either endpoint does not exist or is soft-deleted
     * @throws RouteNotFoundException if both endpoints exist and are active but
     *         no chain of active transports connects them within the bounded
     *         hop count this project searches
     */
    public RouteResponse findRoute(UUID fromId, UUID toId) {
        if (fromId.equals(toId)) {
            throw InvalidRouteRequestException.sameOriginAndTarget(fromId);
        }
        destinationRepository.findActiveById(fromId).orElseThrow(() -> new DestinationNotFoundException(fromId));
        destinationRepository.findActiveById(toId).orElseThrow(() -> new DestinationNotFoundException(toId));

        List<TransportEdge> hops = transportRepository.findPath(fromId, toId);
        if (hops.isEmpty()) {
            throw new RouteNotFoundException(fromId, toId);
        }

        int totalDurationMinutes = hops.stream().mapToInt(TransportEdge::durationMinutes).sum();
        return new RouteResponse(hops.stream().map(this::toResponse).toList(), totalDurationMinutes);
    }

    private void validateModeAndDuration(String mode, int durationMinutes) {
        if (!ALLOWED_MODES.contains(mode)) {
            throw InvalidTransportRequestException.invalidMode(mode, ALLOWED_MODES);
        }
        if (durationMinutes <= 0) {
            throw InvalidTransportRequestException.invalidDuration(durationMinutes);
        }
    }

    /**
     * Loads the active origin destination and enforces the ownership rule
     * shared by create/update/delete: a non-admin caller must be that
     * destination's {@code managerId}.
     */
    private Destination requireOwnedActiveOrigin(UUID fromId, UUID callerId, boolean isAdmin) {
        Destination origin = destinationRepository.findActiveById(fromId)
                .orElseThrow(() -> new DestinationNotFoundException(fromId));
        if (!isAdmin && (origin.getManagerId() == null || !origin.getManagerId().equals(callerId))) {
            throw new InsufficientRoleException("Not allowed to manage another manager's travel");
        }
        return origin;
    }

    private TransportResponse toResponse(TransportEdge edge) {
        return new TransportResponse(edge.id(), edge.mode(), edge.durationMinutes(),
                edge.departureTime(), edge.arrivalTime(),
                new TransportResponse.Target(edge.targetId(), edge.targetName(), edge.targetCountry()));
    }
}
