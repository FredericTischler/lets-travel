import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { TransportMode } from './transport.service';

/** Minimum/maximum accepted by GET /destinations/{fromId}/routes/{toId}?maxHops=. */
export const MIN_ROUTE_HOPS = 1;
export const MAX_ROUTE_HOPS = 6;
export const DEFAULT_ROUTE_HOPS = 4;

/**
 * One leg of a found itinerary: the TRANSPORT taken and the destination it
 * lands on (the origin of the first hop is the `fromId` used in the
 * request). See travel-service RouteHop.java (main-qjv10p).
 */
export interface RouteHop {
  destinationId: string;
  destinationName: string;
  destinationCountry: string;
  mode: TransportMode;
  durationMinutes: number;
  departureTime: string | null;
  arrivalTime: string | null;
}

/** GET /destinations/{fromId}/routes/{toId} response shape. */
export interface RouteSearchResult {
  reachable: boolean;
  hops: RouteHop[];
  totalDurationMinutes: number | null;
}

/**
 * Shortest-hop itinerary search across the TRANSPORT graph (bonus, sujet
 * §11 addendum). Read-only, open to the 3 roles like every other
 * `/destinations/**` GET — no ownership check server-side.
 */
@Injectable({ providedIn: 'root' })
export class RouteSearchService {
  private readonly http = inject(HttpClient);

  findRoute(fromId: string, toId: string, maxHops?: number): Observable<RouteSearchResult> {
    return this.http.get<RouteSearchResult>(
      `${environment.travelApiUrl}/destinations/${fromId}/routes/${toId}`,
      maxHops === undefined ? {} : { params: { maxHops } },
    );
  }
}
