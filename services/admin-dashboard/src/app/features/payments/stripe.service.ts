import { Injectable } from '@angular/core';
import { Stripe, loadStripe } from '@stripe/stripe-js';

import { environment } from '../../../environments/environment';

/**
 * Thin wrapper around Stripe.js (`@stripe/stripe-js`), loaded lazily and only
 * when `environment.stripePublishableKey` is configured (Task 2, "formulaire
 * carte bancaire"): with the default empty key, `isConfigured()` is false and
 * `getStripe()` never touches the network — no third-party script loads and
 * the rest of this app behaves exactly as before (see PendingPaymentComponent,
 * which keeps its current "not integrated" panel in that case).
 *
 * The loaded instance/promise is cached: the `js.stripe.com` script and the
 * publishable-key handshake happen at most once per page load, however many
 * times a card form is mounted/unmounted.
 */
@Injectable({ providedIn: 'root' })
export class StripeService {
  private stripePromise: Promise<Stripe | null> | null = null;

  /** Whether a publishable key is configured — gates every Stripe.js UI in this app. */
  isConfigured(): boolean {
    return environment.stripePublishableKey !== '';
  }

  /**
   * Resolves to the loaded Stripe.js instance, or `null` when unconfigured
   * (no network call in that case) or when Stripe.js itself failed to load.
   */
  getStripe(): Promise<Stripe | null> {
    if (!this.isConfigured()) {
      return Promise.resolve(null);
    }
    if (!this.stripePromise) {
      this.stripePromise = loadStripe(environment.stripePublishableKey);
    }
    return this.stripePromise;
  }
}
