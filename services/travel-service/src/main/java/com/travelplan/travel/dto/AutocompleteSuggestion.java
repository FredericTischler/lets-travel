package com.travelplan.travel.dto;

import java.util.UUID;

/**
 * One autocomplete suggestion returned by {@code GET /destinations/autocomplete}.
 *
 * Deliberately lighter than {@link DestinationResponse}: autocomplete is a
 * live-typing UI feature (the subject asks for "swift" results), so it is
 * served directly from the Elasticsearch completion suggester's {@code
 * _source} without a round-trip back to Neo4j — see
 * {@code DestinationSearchService} for why that round-trip IS done for
 * {@code /destinations/search} but not here.
 */
public class AutocompleteSuggestion {

    private final UUID id;
    private final String name;
    private final String country;

    public AutocompleteSuggestion(UUID id, String name, String country) {
        this.id = id;
        this.name = name;
        this.country = country;
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
}
