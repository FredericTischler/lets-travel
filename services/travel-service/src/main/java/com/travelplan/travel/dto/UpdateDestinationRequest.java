package com.travelplan.travel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Request body for {@code PUT /destinations/{id}}.
 *
 * Full replacement of the mutable fields ({@code name}, {@code country},
 * {@code startDate}, {@code endDate}, {@code activities},
 * {@code accommodations}): the whole activity/accommodation list is
 * replaced by the one given here, same semantics as {@code name}/
 * {@code country} already had. Validated by {@code @Valid} in the
 * controller.
 */
public class UpdateDestinationRequest {

    @NotBlank(message = "must not be blank")
    private String name;

    @NotBlank(message = "must not be blank")
    private String country;

    @NotNull(message = "must not be null")
    private LocalDate startDate;

    @NotNull(message = "must not be null")
    private LocalDate endDate;

    private List<String> activities = new ArrayList<>();

    @Valid
    private List<AccommodationRequest> accommodations = new ArrayList<>();

    public UpdateDestinationRequest() {
        // required for Jackson deserialization
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public List<String> getActivities() {
        return activities;
    }

    public void setActivities(List<String> activities) {
        this.activities = activities == null ? new ArrayList<>() : activities;
    }

    public List<AccommodationRequest> getAccommodations() {
        return accommodations;
    }

    public void setAccommodations(List<AccommodationRequest> accommodations) {
        this.accommodations = accommodations == null ? new ArrayList<>() : accommodations;
    }
}
