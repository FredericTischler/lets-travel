package com.travelplan.travel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Request body for {@code POST /destinations}.
 *
 * Validated by {@code @Valid} in the controller. Constraint violations are
 * handled by {@link com.travelplan.travel.exception.GlobalExceptionHandler}
 * and returned as HTTP 400. {@code activities}/{@code accommodations} are
 * optional: omitted or {@code null} is treated as an empty list. The
 * {@code endDate} >= {@code startDate} and per-accommodation
 * {@code checkOut} >= {@code checkIn} rules are business rules, not
 * annotations — checked by
 * {@link com.travelplan.travel.service.DestinationService}.
 */
public class CreateDestinationRequest {

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

    public CreateDestinationRequest() {
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
