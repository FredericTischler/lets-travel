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
 * POST /destinations/{fromId}/transports. See travel-service TransportResponse.java.
 * Directed, single-hop: this is always the TARGET side of the relationship,
 * the origin is implicit (the destination id used in the request path).
 */
export interface Transport {
  mode: TransportMode;
  durationMinutes: number;
  destinationId: string;
  destinationName: string;
  destinationCountry: string;
}

/**
 * Access to the travel-service TRANSPORT relationship endpoints, nested
 * under /destinations/{id}/transports. The auth interceptor attaches the
 * Bearer token automatically for every request whose URL starts with
 * environment.travelApiUrl.
 *
 * No update()/delete(): travel-service exposes no PATCH/DELETE on Transport
 * (increment 2 scope), so this stays create + list-outgoing only.
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
}
