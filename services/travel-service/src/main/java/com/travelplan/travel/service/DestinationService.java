package com.travelplan.travel.service;

import com.travelplan.travel.dto.AccommodationRequest;
import com.travelplan.travel.dto.AccommodationResponse;
import com.travelplan.travel.dto.ActivityResponse;
import com.travelplan.travel.dto.CreateDestinationRequest;
import com.travelplan.travel.dto.DestinationResponse;
import com.travelplan.travel.dto.UpdateDestinationRequest;
import com.travelplan.travel.entity.Destination;
import com.travelplan.travel.exception.DestinationNotFoundException;
import com.travelplan.travel.exception.InsufficientRoleException;
import com.travelplan.travel.exception.InvalidDestinationRequestException;
import com.travelplan.travel.repository.AccommodationInput;
import com.travelplan.travel.repository.AccommodationRepository;
import com.travelplan.travel.repository.AccommodationView;
import com.travelplan.travel.repository.ActivityRepository;
import com.travelplan.travel.repository.ActivityView;
import com.travelplan.travel.repository.DestinationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for the {@link Destination} node.
 *
 * "Delete" always means soft-delete: {@code deletedAt} is set to now(), the
 * node stays. findById / findAll silently filter out soft-deleted nodes
 * (callers receive a 404 / empty list, not a soft-deleted node).
 *
 * Activities and accommodations are owned exclusively by a destination and
 * are managed as whole-list replacements through {@link ActivityRepository}/
 * {@link AccommodationRepository} — see the Javadoc on
 * {@link Destination#getActivities()} for why the standard Spring Data
 * Neo4j aggregate save/load flow is bypassed. A destination soft-delete
 * never touches its activities/accommodations: they stay physically in the
 * graph (no DETACH DELETE, same philosophy as {@code TRANSPORT}) but become
 * unreachable, since the only read path goes through
 * {@link #findById}/{@link #findAll}, which both filter out soft-deleted
 * destinations before any activity/accommodation is fetched.
 *
 * <p>Since docs/lets-travel-architecture-decisions.md §2, {@code update}/
 * {@code delete} are ownership-aware: a {@code TRAVEL_MANAGER} caller may
 * only act on a destination whose {@code managerId} is their own id, an
 * {@code ADMIN} caller bypasses this check entirely. Reads stay open to any
 * role (the catalogue is public) — ownership is not checked there. The
 * {@code managerId}/ownership check for creation is the controller's
 * responsibility ({@code TokenValidationService.requireOwnerOrAdmin} against
 * the request body's {@code managerId}), the same split payment-service uses
 * for {@code CreateManualPaymentRequest.userId}.</p>
 */
@Service
@Transactional(readOnly = true)
public class DestinationService {

    private final DestinationRepository destinationRepository;
    private final ActivityRepository activityRepository;
    private final AccommodationRepository accommodationRepository;

    public DestinationService(DestinationRepository destinationRepository, ActivityRepository activityRepository,
                               AccommodationRepository accommodationRepository) {
        this.destinationRepository = destinationRepository;
        this.activityRepository = activityRepository;
        this.accommodationRepository = accommodationRepository;
    }

    /**
     * Create a new destination, along with its activities and
     * accommodations.
     *
     * @throws InvalidDestinationRequestException if endDate is before startDate,
     *         or an accommodation's checkOut is before its checkIn
     */
    @Transactional
    public DestinationResponse create(CreateDestinationRequest request) {
        validateDates(request.getStartDate(), request.getEndDate());
        validateAccommodations(request.getAccommodations());

        Destination destination = new Destination(
                request.getName(), request.getCountry(), request.getStartDate(), request.getEndDate(),
                request.getManagerId(), request.getPrice(), request.getCapacity());
        Destination saved = destinationRepository.save(destination);

        activityRepository.replaceForDestination(saved.getId(), request.getActivities());
        accommodationRepository.replaceForDestination(saved.getId(), toAccommodationInputs(request.getAccommodations()));

        return buildResponse(saved);
    }

    /**
     * Find an active destination by id.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     */
    public DestinationResponse findById(UUID id) {
        Destination destination = destinationRepository.findActiveById(id)
                .orElseThrow(() -> new DestinationNotFoundException(id));
        return buildResponse(destination);
    }

    /**
     * Return all active destinations.
     */
    public List<DestinationResponse> findAll() {
        return destinationRepository.findAllActive().stream()
                .map(this::buildResponse)
                .collect(Collectors.toList());
    }

    /**
     * Replace the mutable fields ({@code name}, {@code country},
     * {@code startDate}, {@code endDate}, {@code price}, {@code capacity})
     * and the whole activity/accommodation lists of an active destination.
     * {@code managerId} is never touched — see the class-level Javadoc.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         destination's {@code managerId} is not {@code callerId}
     * @throws InvalidDestinationRequestException if endDate is before startDate,
     *         or an accommodation's checkOut is before its checkIn
     */
    @Transactional
    public DestinationResponse update(UUID id, UpdateDestinationRequest request, UUID callerId, boolean isAdmin) {
        Destination destination = destinationRepository.findActiveById(id)
                .orElseThrow(() -> new DestinationNotFoundException(id));
        requireOwnership(destination, callerId, isAdmin);

        validateDates(request.getStartDate(), request.getEndDate());
        validateAccommodations(request.getAccommodations());

        destination.setName(request.getName());
        destination.setCountry(request.getCountry());
        destination.setStartDate(request.getStartDate());
        destination.setEndDate(request.getEndDate());
        destination.setPrice(request.getPrice());
        destination.setCapacity(request.getCapacity());
        Destination saved = destinationRepository.save(destination);

        activityRepository.replaceForDestination(id, request.getActivities());
        accommodationRepository.replaceForDestination(id, toAccommodationInputs(request.getAccommodations()));

        return buildResponse(saved);
    }

    /**
     * Soft-delete an active destination (sets deletedAt = now()). Its
     * activities/accommodations are left untouched in the graph — see the
     * class-level Javadoc.
     *
     * @throws DestinationNotFoundException if the destination does not exist or is already soft-deleted
     * @throws InsufficientRoleException if {@code isAdmin} is false and the
     *         destination's {@code managerId} is not {@code callerId}
     */
    @Transactional
    public void delete(UUID id, UUID callerId, boolean isAdmin) {
        Destination destination = destinationRepository.findActiveById(id)
                .orElseThrow(() -> new DestinationNotFoundException(id));
        requireOwnership(destination, callerId, isAdmin);
        destination.setDeletedAt(OffsetDateTime.now());
        destinationRepository.save(destination);
    }

    /**
     * Enforces the ownership half of RBAC (docs/lets-travel-architecture-decisions.md
     * §2): an {@code ADMIN} caller always passes (full oversight); otherwise
     * the caller must be the destination's own {@code managerId}. Reads never
     * call this — the catalogue is public, only mutation is ownership-aware.
     */
    private void requireOwnership(Destination destination, UUID callerId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (destination.getManagerId() == null || !destination.getManagerId().equals(callerId)) {
            throw new InsufficientRoleException("Not allowed to manage another manager's travel");
        }
    }

    private DestinationResponse buildResponse(Destination destination) {
        List<ActivityResponse> activities = activityRepository.findActiveForDestination(destination.getId()).stream()
                .map(this::toActivityResponse)
                .collect(Collectors.toList());
        List<AccommodationResponse> accommodations =
                accommodationRepository.findActiveForDestination(destination.getId()).stream()
                        .map(this::toAccommodationResponse)
                        .collect(Collectors.toList());
        return DestinationResponse.from(destination, activities, accommodations);
    }

    private ActivityResponse toActivityResponse(ActivityView view) {
        return new ActivityResponse(view.id(), view.name());
    }

    private AccommodationResponse toAccommodationResponse(AccommodationView view) {
        return new AccommodationResponse(view.id(), view.name(), view.type(), view.checkIn(), view.checkOut());
    }

    private List<AccommodationInput> toAccommodationInputs(List<AccommodationRequest> requests) {
        return requests.stream()
                .map(r -> new AccommodationInput(r.getName(), r.getType(), r.getCheckIn(), r.getCheckOut()))
                .collect(Collectors.toList());
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw InvalidDestinationRequestException.endDateBeforeStartDate(startDate, endDate);
        }
    }

    private void validateAccommodations(List<AccommodationRequest> accommodations) {
        for (AccommodationRequest accommodation : accommodations) {
            LocalDate checkIn = accommodation.getCheckIn();
            LocalDate checkOut = accommodation.getCheckOut();
            if (checkIn != null && checkOut != null && checkOut.isBefore(checkIn)) {
                throw InvalidDestinationRequestException.accommodationCheckOutBeforeCheckIn(checkIn, checkOut);
            }
        }
    }
}
