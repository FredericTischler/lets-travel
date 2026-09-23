import { Injectable } from '@angular/core';
import { Stripe, loadStripe } from '@stripe/stripe-js';

import { environment } from '../../../environments/environment';

/**
 * Loads Stripe.js once (its own recommendation: one `loadStripe()` call per
 * page, cached) and hands back the same instance to every caller. Returns
 * `null` without ever calling `loadStripe()` when no publishable key is
 * configured, so the payment screen can fall back to an explicit
 * "not configured" message instead of a network error.
 */
@Injectable({ providedIn: 'root' })
export class StripeCheckoutService {
  private stripePromise: Promise<Stripe | null> | null = null;

  load(): Promise<Stripe | null> {
    if (!environment.stripePublishableKey) {
      return Promise.resolve(null);
    }
    this.stripePromise ??= loadStripe(environment.stripePublishableKey);
    return this.stripePromise;
  }
}
