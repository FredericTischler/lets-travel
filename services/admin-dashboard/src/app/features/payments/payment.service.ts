import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';

/** Status values the payment-service backend can report or accept. */
export type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

/** How a payment is made (payment-service `PaymentProvider`). */
export type PaymentProvider = 'MANUAL' | 'STRIPE' | 'PAYPAL';

export const PAYMENT_PROVIDER_LABELS: Record<PaymentProvider, string> = {
  MANUAL: 'Paiement manuel',
  STRIPE: 'Carte bancaire (Stripe)',
  PAYPAL: 'PayPal',
};

/**
 * Shape of the payment-service GET/POST/PATCH /payments response items.
 * See services/payment-service PaymentResponse.java. For a PayPal payment
 * `externalReference` is the PayPal order id (what the capture call needs).
 */
export interface Payment {
  id: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  externalReference: string | null;
  createdAt: string;
  provider?: PaymentProvider;
  travelId?: string | null;
  subscriptionRef?: string | null;
}

/**
 * CRUD access to the payment-service /payments endpoints. The auth
 * interceptor attaches the Bearer token automatically for every request
 * whose URL starts with environment.paymentApiUrl.
 */
@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);

  list(): Observable<Payment[]> {
    return this.http.get<Payment[]>(`${environment.paymentApiUrl}/payments`);
  }

  /**
   * Creates a payment auto-referenced to the currently logged-in user
   * (userId read from the JWT via AuthService.getCurrentUserId(), not from
   * a form field — see CreateManualPaymentRequest.java, userId is
   * @NotNull).
   */
  create(amount: number, currency: string): Observable<Payment> {
    const userId = this.authService.getCurrentUserId();
    if (!userId) {
      return throwError(() => new Error('Utilisateur non authentifié : impossible de créer un paiement.'));
    }

    return this.http.post<Payment>(`${environment.paymentApiUrl}/payments`, { userId, amount, currency });
  }

  /** One payment — its owner or an admin (used to follow a pending subscription payment). */
  get(id: string): Observable<Payment> {
    return this.http.get<Payment>(`${environment.paymentApiUrl}/payments/${id}`);
  }

  /**
   * Captures a payer-approved PayPal order (`POST /payments/paypal/{orderId}/capture`).
   * 404: unknown order; 409: the payment is already COMPLETED/FAILED; 502: PayPal refused.
   */
  capturePayPal(orderId: string): Observable<Payment> {
    return this.http.post<Payment>(
      `${environment.paymentApiUrl}/payments/paypal/${encodeURIComponent(orderId)}/capture`,
      null,
    );
  }

  updateStatus(id: string, status: 'COMPLETED' | 'FAILED'): Observable<Payment> {
    return this.http.patch<Payment>(`${environment.paymentApiUrl}/payments/${id}/status`, { status });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${environment.paymentApiUrl}/payments/${id}`);
  }
}