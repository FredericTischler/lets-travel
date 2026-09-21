import { Injectable } from '@angular/core';

import { PaymentProvider } from '../payments/payment.service';

/** What this browser remembers of a payment it started (never a secret). */
export interface RememberedPayment {
  provider: PaymentProvider;
  /** PayPal approval URL, to resume a payment left half-way. */
  approveUrl: string | null;
  /** PayPal order id, the argument of the capture call. */
  orderId: string | null;
}

const STORAGE_KEY = 'admin-dashboard.pending-payments';

/**
 * Remembers, in this browser only, how the payments started here were begun —
 * chiefly the PayPal approval URL, which the API returns once at subscribe time
 * and never again. It lets the traveler resume a payment from "Mes abonnements"
 * or after the PayPal round trip. Stripe's `clientSecret` is deliberately never
 * stored. Storage can be blocked or full: every access is guarded and the
 * screens work (with less convenience) without it.
 */
@Injectable({ providedIn: 'root' })
export class PendingPaymentStore {
  get(paymentId: string): RememberedPayment | null {
    return this.read()[paymentId] ?? null;
  }

  remember(paymentId: string, payment: RememberedPayment): void {
    const all = this.read();
    all[paymentId] = payment;
    this.write(all);
  }

  forget(paymentId: string): void {
    const all = this.read();
    delete all[paymentId];
    this.write(all);
  }

  private read(): Record<string, RememberedPayment> {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      const parsed: unknown = raw ? JSON.parse(raw) : {};
      return parsed && typeof parsed === 'object' ? (parsed as Record<string, RememberedPayment>) : {};
    } catch {
      return {};
    }
  }

  private write(value: Record<string, RememberedPayment>): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
    } catch {
      // Storage unavailable: the payment can still be resumed from the original screen.
    }
  }
}
