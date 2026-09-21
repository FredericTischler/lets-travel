import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * One line of GET /travelers/me/recommendations (travel-service
 * RecommendationResponse.java). `score` is the sum of the signed points that end
 * each `reasons` entry — that is what makes a recommendation checkable by hand
 * (docs/lets-travel-architecture-decisions.md §7). The reasons are written by the
 * backend, in English, and shown verbatim.
 */
export interface Recommendation {
  destinationId: string;
  name: string;
  country: string;
  startDate: string;
  endDate: string;
  price: number | null;
  score: number;
  reasons: string[];
}

/** Access to the Neo4j-backed personalised recommendations of the caller. */
@Injectable({ providedIn: 'root' })
export class RecommendationService {
  private readonly http = inject(HttpClient);

  mine(limit = 6): Observable<Recommendation[]> {
    return this.http.get<Recommendation[]>(
      `${environment.travelApiUrl}/travelers/me/recommendations`,
      { params: { limit } },
    );
  }
}
