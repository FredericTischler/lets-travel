import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * Fixed, non-extensible set of transport modes enforced by travel-service
 * (`TransportService.ALLOWED_MODES`) — not a UI-owned enum, just mirrored
 * here so the select doesn't propose values the backend would reject.
 */
export const TRANSPORT_MODES = ['TRAIN', 'PLANE', 'BUS', 'CAR', 'BOAT'] as const;

export type TransportMode = (typeof TRANSPORT_MODES)[number];

/**
 * Shape of one element returned by GET /destinations/{id}/transports, of
 * POST/PUT .../transports(/{id}), and of each hop of a `Route`. See
 * travel-service TransportResponse.java. Directed, single-hop: this is
 * always the TARGET side of the relationship — the origin is implicit (the
 * destination id used in the request path, or the previous hop's
 * `destinationId` inside a route). `id` addresses this specific transport
 * for PUT/DELETE — never the Neo4j-internal relationship id, an
 * app-assigned UUID (same convention as a destination's own `id`).
 */
export interface Transport {
  id: string;
  mode: TransportMode;
  durationMinutes: number;
  destinationId: string;
  destinationName: string;
  destinationCountry: string;
}

/**
 * GET /destinations/{fromId}/routes/{toId} response: the fewest-hops chain
 * of active transports from `fromId` to `toId`, in order, plus the sum of
 * every hop's `durationMinutes`. "Fewest hops", not "lowest total duration"
 * — see travel-service TransportRepository's PATH_QUERY for why a true
 * weighted shortest-path is out of scope (no APOC plugin in this project).
 */
export interface Route {
  hops: Transport[];
  totalDurationMinutes: number;
}

/**
 * Access to the travel-service TRANSPORT relationship endpoints, nested
 * under /destinations/{id}/transports, plus the multi-hop route lookup
 * under /destinations/{fromId}/routes/{toId}. The auth interceptor attaches
 * the Bearer token automatically for every request whose URL starts with
 * environment.travelApiUrl.
 */
@Injectable({ providedIn: 'root' })
export class TransportService {
  private readonly http = inject(HttpClient);

  listOutgoing(fromId: string): Observable<Transport[]> {
    return this.http.get<Transport[]>(
      `${environment.travelApiUrl}/destinations/${fromId}/transports`,
    );
  }

  create(
    fromId: string,
    toDestinationId: string,
    mode: TransportMode,
    durationMinutes: number,
  ): Observable<Transport> {
    return this.http.post<Transport>(
      `${environment.travelApiUrl}/destinations/${fromId}/transports`,
      {
        toDestinationId,
        mode,
        durationMinutes,
      },
    );
  }

  /** The endpoints (`fromId`/the target) never change — only mode/durationMinutes do. */
  update(
    fromId: string,
    transportId: string,
    mode: TransportMode,
    durationMinutes: number,
  ): Observable<Transport> {
    return this.http.put<Transport>(
      `${environment.travelApiUrl}/destinations/${fromId}/transports/${transportId}`,
      { mode, durationMinutes },
    );
  }

  delete(fromId: string, transportId: string): Observable<void> {
    return this.http.delete<void>(
      `${environment.travelApiUrl}/destinations/${fromId}/transports/${transportId}`,
    );
  }

  /** 404 (via the global HTTP error handling) when no chain connects the two within the backend's bounded hop count. */
  findRoute(fromId: string, toId: string): Observable<Route> {
    return this.http.get<Route>(
      `${environment.travelApiUrl}/destinations/${fromId}/routes/${toId}`,
    );
  }
}
