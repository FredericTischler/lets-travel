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
  activities: string[];
  accommodations: AccommodationInput[];
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

  create(input: DestinationInput): Observable<Destination> {
    return this.http.post<Destination>(`${environment.travelApiUrl}/destinations`, input);
  }

  update(id: string, input: DestinationInput): Observable<Destination> {
    return this.http.put<Destination>(`${environment.travelApiUrl}/destinations/${id}`, input);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${environment.travelApiUrl}/destinations/${id}`);
  }
}
