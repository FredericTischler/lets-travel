import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

export type BadgeCode = 'EXPLORER' | 'GLOBETROTTER' | 'CRITIC';

/** One fixed badge tier (travel-service TravelerBadgesResponse.Badge). */
export interface TravelerBadge {
  code: BadgeCode;
  threshold: number;
  progress: number;
  earned: boolean;
}

/**
 * GET /travelers/me/badges (bonus feature, docs/lets-travel-architecture-decisions.md
 * §12): raw counts plus every fixed tier, earned or not. The backend never
 * sends a label — `code` is a stable identifier, the front owns the
 * French label/icon shown for each (see `BADGE_LABELS` below), same as it
 * already owns `PAYMENT_PROVIDER_LABELS`.
 */
export interface TravelerBadges {
  destinationsVisited: number;
  countriesVisited: number;
  reviewsGiven: number;
  badges: TravelerBadge[];
}

export const BADGE_LABELS: Record<BadgeCode, { label: string; icon: string }> = {
  EXPLORER: { label: 'Explorateur', icon: '🧭' },
  GLOBETROTTER: { label: 'Globe-trotter', icon: '🌍' },
  CRITIC: { label: 'Critique', icon: '✍️' },
};

/** Access to the caller's traveler badges. */
@Injectable({ providedIn: 'root' })
export class BadgeService {
  private readonly http = inject(HttpClient);

  mine(): Observable<TravelerBadges> {
    return this.http.get<TravelerBadges>(`${environment.travelApiUrl}/travelers/me/badges`);
  }
}
