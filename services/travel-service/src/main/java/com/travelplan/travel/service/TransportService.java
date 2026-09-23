package com.travelplan.travel.service;

import com.travelplan.travel.dto.CreateTransportRequest;
import com.travelplan.travel.dto.TransportResponse;
import com.travelplan.travel.entity.Destination;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.exception.InvalidTransportRequestException;
import com.travelplan.travel.repository.DestinationRepository;
import com.travelplan.travel.repository.TransportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for the {@code TRANSPORT} relationship between two
 * destinations.
 *
 * Increment 2 scope: create a directed, single-hop transport link and list
 * the destinations reachable in exactly one hop. No pathfinding, no second
 * node type, no relationship update/delete, no anti-duplicate protection —
 * see {@link TransportRepository} for the rationale of the last point.
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
        if (!ALLOWED_MODES.contains(request.getMode())) {
            throw InvalidTransportRequestException.invalidMode(request.getMode(), ALLOWED_MODES);
        }
        if (request.getDurationMinutes() <= 0) {
            throw InvalidTransportRequestException.invalidDuration(request.getDurationMinutes());
        }

        Destination origin = destinationRepository.findActiveById(fromId)
                .orElseThrow(() -> new DestinationNotFoundException(fromId));
        if (!isAdmin && (origin.getManagerId() == null || !origin.getManagerId().equals(callerId))) {
            throw new InsufficientRoleException("Not allowed to manage another manager's travel");
        }
        Destination target = destinationRepository.findActiveById(toId)
                .orElseThrow(() -> new DestinationNotFoundException(toId));

        transportRepository.create(origin.getId(), target.getId(), request.getMode(), request.getDurationMinutes(),
                request.getDepartureTime(), request.getArrivalTime());

        return new TransportResponse(request.getMode(), request.getDurationMinutes(),
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
                .map(edge -> new TransportResponse(edge.mode(), edge.durationMinutes(),
                        edge.departureTime(), edge.arrivalTime(),
                        edge.targetId(), edge.targetName(), edge.targetCountry()))
                .toList();
    }
}