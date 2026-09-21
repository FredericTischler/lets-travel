import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** `SUBSCRIBED` relation status (travel-service). */
export type SubscriptionStatus = 'ACTIVE' | 'CANCELLED';

/**
 * One row of POST/GET /destinations/{id}/subscriptions
 * (travel-service SubscriptionResponse.java). Only the traveler's id is
 * exposed — there is no endpoint resolving it to an email/profile for a
 * Travel Manager.
 */
export interface Subscription {
  destinationId: string;
  travelerId: string;
  status: SubscriptionStatus;
  subscribedAt: string;
  cancelledAt: string | null;
}

/**
 * One row of GET /travelers/me/subscriptions
 * (travel-service TravelerSubscriptionResponse.java): a subscription plus a
 * summary of its destination.
 */
export interface TravelerSubscription {
  destinationId: string;
  destinationName: string;
  destinationCountry: string;
  destinationStartDate: string;
  status: SubscriptionStatus;
  subscribedAt: string;
  cancelledAt: string | null;
}

/**
 * Access to the travel-service subscription endpoints. The subscriber of the
 * "self" calls is always the caller's own JWT subject — never sent by the
 * client.
 */
@Injectable({ providedIn: 'root' })
export class SubscriptionService {
  private readonly http = inject(HttpClient);

  private url(destinationId: string): string {
    return `${environment.travelApiUrl}/destinations/${destinationId}/subscriptions`;
  }

  /** Subscribe the caller. 409: travel already started, or already subscribed. */
  subscribe(destinationId: string): Observable<Subscription> {
    return this.http.post<Subscription>(this.url(destinationId), null);
  }

  /** Unsubscribe the caller. 409 within 3 days of the start date; 404 if not subscribed. */
  unsubscribe(destinationId: string): Observable<void> {
    return this.http.delete<void>(this.url(destinationId));
  }

  /** The caller's own history, every status. */
  mine(): Observable<TravelerSubscription[]> {
    return this.http.get<TravelerSubscription[]>(
      `${environment.travelApiUrl}/travelers/me/subscriptions`,
    );
  }

  /** Every subscription (any status) of a destination — owning manager or admin only. */
  listForDestination(destinationId: string): Observable<Subscription[]> {
    return this.http.get<Subscription[]>(this.url(destinationId));
  }

  /** Manager/admin unsubscribes a traveler; the 3-day cutoff does not apply. */
  forceUnsubscribe(destinationId: string, travelerId: string): Observable<void> {
    return this.http.delete<void>(`${this.url(destinationId)}/${travelerId}`);
  }
}
