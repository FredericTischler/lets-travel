import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** One stop of an itinerary suggestion (travel-service ItinerarySuggestionResponse.Stop). */
export interface ItineraryStop {
  destinationId: string;
  name: string;
  country: string;
  startDate: string;
  endDate: string;
  price: number | null;
  score: number;
}

/**
 * One line of GET /travelers/me/itinerary-suggestions (travel-service
 * ItinerarySuggestionResponse.java): a chain of 2 to 3 destinations connected
 * by active TRANSPORT edges, each already eligible for the caller. `score` is
 * the sum of every stop's own recommendation score (docs/lets-travel-architecture-decisions.md
 * §12) — no new scoring, only their aggregation. `reasons` are written by the
 * backend, in English, and shown verbatim (same as a single recommendation).
 */
export interface ItinerarySuggestion {
  stops: ItineraryStop[];
  score: number;
  totalDurationMinutes: number;
  reasons: string[];
}

/** Access to the Neo4j-backed itinerary suggestions of the caller (bonus feature). */
@Injectable({ providedIn: 'root' })
export class ItineraryService {
  private readonly http = inject(HttpClient);

  mine(): Observable<ItinerarySuggestion[]> {
    return this.http.get<ItinerarySuggestion[]>(
      `${environment.travelApiUrl}/travelers/me/itinerary-suggestions`,
    );
  }
}
