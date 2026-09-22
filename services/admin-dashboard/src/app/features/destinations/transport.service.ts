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
 * Shape of one element returned by GET /destinations/{id}/transports and by
 * POST/PATCH /destinations/{fromId}/transports(/{transportId}). See
 * travel-service TransportResponse.java. Directed, single-hop: this is
 * always the TARGET side of the relationship, the origin is implicit (the
 * destination id used in the request path).
 *
 * `id` is additive (travel-service main-qjv10p): the relationship's own
 * identifier, needed to address PATCH/DELETE below — a transport has no
 * other stable key (destinationId alone doesn't disambiguate two transports
 * to the same target with different modes, were that ever allowed).
 */
export interface Transport {
  id: string;
  mode: TransportMode;
  durationMinutes: number;
  destinationId: string;
  destinationName: string;
  destinationCountry: string;
}

/** PATCH /destinations/{fromId}/transports/{transportId} request body. */
export interface TransportUpdateInput {
  mode: TransportMode;
  durationMinutes: number;
  departureTime?: string | null;
  arrivalTime?: string | null;
}

/**
 * Access to the travel-service TRANSPORT relationship endpoints, nested
 * under /destinations/{id}/transports. The auth interceptor attaches the
 * Bearer token automatically for every request whose URL starts with
 * environment.travelApiUrl.
 *
 * update()/delete() are reserved server-side to the manager owning `fromId`
 * (or an ADMIN) — the same ownership rule already enforced on create().
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

  update(fromId: string, transportId: string, input: TransportUpdateInput): Observable<Transport> {
    return this.http.patch<Transport>(
      `${environment.travelApiUrl}/destinations/${fromId}/transports/${transportId}`,
      input,
    );
  }

  delete(fromId: string, transportId: string): Observable<void> {
    return this.http.delete<void>(
      `${environment.travelApiUrl}/destinations/${fromId}/transports/${transportId}`,
    );
  }
}
