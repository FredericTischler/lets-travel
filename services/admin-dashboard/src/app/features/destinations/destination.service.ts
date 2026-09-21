import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * One activity attached to a destination, as returned by the API.
 * See services/travel-service ActivityResponse.java.
 */
export interface Activity {
  id: string;
  name: string;
}

/**
 * One accommodation attached to a destination, as returned by the API.
 * `type` is a free-text field server-side (no enum enforced by
 * travel-service), `checkIn`/`checkOut` are optional (ISO date strings,
 * `null` when the stay spans the whole destination visit).
 * See services/travel-service AccommodationResponse.java.
 */
export interface Accommodation {
  id: string;
  name: string;
  type: string;
  checkIn: string | null;
  checkOut: string | null;
}

/**
 * Shape of the travel-service GET/POST/PUT /destinations response items.
 * See services/travel-service DestinationResponse.java.
 */
export interface Destination {
  id: string;
  name: string;
  country: string;
  startDate: string;
  endDate: string;
  /** Always server-derived from startDate/endDate, never sent on writes. */
  durationDays: number;
  /**
   * Owning Travel Manager (identity-service user id, application-level
   * reference). Null only for destinations created before Phase 1.
   */
  managerId: string | null;
  price: number | null;
  capacity: number | null;
  activities: Activity[];
  accommodations: Accommodation[];
  createdAt: string;
}

/** One accommodation entry as sent in a create/update request body. */
export interface AccommodationInput {
  name: string;
  type: string;
  checkIn?: string | null;
  checkOut?: string | null;
}

/**
 * Request body shared by POST /destinations and PUT /destinations/{id}.
 * See services/travel-service CreateDestinationRequest.java and
 * UpdateDestinationRequest.java (identical shape).
 */
export interface DestinationInput {
  name: string;
  country: string;
  startDate: string;
  endDate: string;
  /** >= 0 (backend `@PositiveOrZero`). */
  price: number;
  /** >= 1 (backend `@Positive`). */
  capacity: number;
  activities: string[];
  accommodations: AccommodationInput[];
}

/**
 * POST /destinations body: the update shape plus the owning manager. A
 * TRAVEL_MANAGER may only send their own id (403 otherwise), an ADMIN any.
 * PUT deliberately has no `managerId` — ownership is never reassigned.
 */
export interface DestinationCreateInput extends DestinationInput {
  managerId: string;
}

/**
 * One item of GET /destinations/autocomplete (travel-service
 * AutocompleteSuggestion.java) — lighter than a full Destination.
 */
export interface AutocompleteSuggestion {
  id: string;
  name: string;
  country: string;
}

/**
 * CRUD access to the travel-service /destinations endpoints. The auth
 * interceptor attaches the Bearer token automatically for every request
 * whose URL starts with environment.travelApiUrl.
 */
@Injectable({ providedIn: 'root' })
export class DestinationService {
  private readonly http = inject(HttpClient);

  list(): Observable<Destination[]> {
    return this.http.get<Destination[]>(`${environment.travelApiUrl}/destinations`);
  }

  get(id: string): Observable<Destination> {
    return this.http.get<Destination>(`${environment.travelApiUrl}/destinations/${id}`);
  }

  /**
   * Elasticsearch full-text search (name, country, activities,
   * accommodations...). Answers 503 when Elasticsearch is unreachable.
   */
  search(query: string): Observable<Destination[]> {
    return this.http.get<Destination[]>(`${environment.travelApiUrl}/destinations/search`, {
      params: { q: query },
    });
  }

  /** Elasticsearch completion suggester on name/country (max 10). 503 if ES is down. */
  autocomplete(prefix: string): Observable<AutocompleteSuggestion[]> {
    return this.http.get<AutocompleteSuggestion[]>(
      `${environment.travelApiUrl}/destinations/autocomplete`,
      { params: { prefix } },
    );
  }

  create(input: DestinationCreateInput): Observable<Destination> {
    return this.http.post<Destination>(`${environment.travelApiUrl}/destinations`, input);
  }

  update(id: string, input: DestinationInput): Observable<Destination> {
    return this.http.put<Destination>(`${environment.travelApiUrl}/destinations/${id}`, input);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${environment.travelApiUrl}/destinations/${id}`);
  }
}
