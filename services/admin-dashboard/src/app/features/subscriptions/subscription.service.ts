import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PaymentProvider } from '../payments/payment.service';

/**
 * `SUBSCRIBED` relation status (travel-service). `PENDING_PAYMENT` holds a seat
 * until `expiresAt`; `EXPIRED` is derived at read time from an unpaid
 * `PENDING_PAYMENT` past its deadline (docs §4 addendum).
 */
export type SubscriptionStatus = 'ACTIVE' | 'PENDING_PAYMENT' | 'CANCELLED' | 'EXPIRED';

/** Body of a paid subscription: how the traveler pays. Free travels need no body. */
export interface SubscribeRequest {
  provider: PaymentProvider;
  currency?: string;
}

/**
 * What the traveler needs to complete a payment, returned once by the subscribe
 * call of a paid travel (travel-service PaymentCheckoutResponse.java).
 * `clientSecret` (Stripe) is deliberately never stored or displayed here.
 */
export interface PaymentCheckout {
  paymentId: string;
  provider: PaymentProvider;
  status: string;
  clientSecret: string | null;
  approveUrl: string | null;
}

/**
 * One row of POST/GET /destinations/{id}/subscriptions
 * (travel-service SubscriptionResponse.java). Only the traveler's id is
 * exposed — there is no endpoint resolving it to an email/profile for a
 * Travel Manager. `payment` is only present in the response to a paid subscribe.
 */
export interface Subscription {
  id?: string | null;
  destinationId: string;
  travelerId: string;
  status: SubscriptionStatus;
  subscribedAt: string;
  cancelledAt: string | null;
  expiresAt?: string | null;
  paymentId?: string | null;
  amount?: number | null;
  currency?: string | null;
  payment?: PaymentCheckout | null;
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
  subscriptionId?: string | null;
  paymentId?: string | null;
  expiresAt?: string | null;
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

  /**
   * Subscribe the caller. A free travel is ACTIVE at once (no body); a paid one
   * needs `request.provider` and answers PENDING_PAYMENT with a `payment` object.
   * 400: paid travel without provider; 409: already started / already subscribed /
   * full; 502: payment-service could not create the payment (nothing is held).
   */
  subscribe(destinationId: string, request?: SubscribeRequest): Observable<Subscription> {
    return this.http.post<Subscription>(this.url(destinationId), request ?? null);
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
