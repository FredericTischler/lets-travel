package com.travelplan.travel.dto;

import com.travelplan.travel.entity.Destination;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API response for a destination resource.
 *
 * Intentionally omits {@code deletedAt}: that field is an internal
 * soft-delete implementation detail and must never be exposed over the API.
 *
 * {@code activities}/{@code accommodations} are not part of the
 * {@link Destination} aggregate load (see the Javadoc on
 * {@link Destination#getActivities()}), so {@link #from} takes them as
 * separate parameters rather than reading them off the entity. {@code
 * durationDays} is never stored — it is {@link Destination#getDurationDays()},
 * always derived from {@code startDate}/{@code endDate}.
 */
public class DestinationResponse {

    private final UUID id;
    private final String name;
    private final String country;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Long durationDays;
    private final List<ActivityResponse> activities;
    private final List<AccommodationResponse> accommodations;
    private final OffsetDateTime createdAt;

    private DestinationResponse(UUID id, String name, String country, LocalDate startDate, LocalDate endDate,
                                 Long durationDays, List<ActivityResponse> activities,
                                 List<AccommodationResponse> accommodations, OffsetDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.country = country;
        this.startDate = startDate;
        this.endDate = endDate;
        this.durationDays = durationDays;
        this.activities = activities;
        this.accommodations = accommodations;
        this.createdAt = createdAt;
    }

    public static DestinationResponse from(Destination destination, List<ActivityResponse> activities,
                                            List<AccommodationResponse> accommodations) {
        return new DestinationResponse(
                destination.getId(),
                destination.getName(),
                destination.getCountry(),
                destination.getStartDate(),
                destination.getEndDate(),
                destination.getDurationDays(),
                activities,
                accommodations,
                destination.getCreatedAt());
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Long getDurationDays() {
        return durationDays;
    }

    public List<ActivityResponse> getActivities() {
        return activities;
    }

    public List<AccommodationResponse> getAccommodations() {
        return accommodations;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
