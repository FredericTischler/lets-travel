import { Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { PaymentService } from '../payments/payment.service';
import { captureErrorMessage } from './pending-payment.component';
import { PendingPaymentStore } from './pending-payment.store';

/**
 * Where PayPal sends the payer back (`/paypal/return?token=<orderId>&PayerID=…`):
 * captures the approved order (`POST /payments/paypal/{orderId}/capture`) and
 * tells the traveler what happened. The subscription itself turns ACTIVE
 * asynchronously (payment-service notifies travel-service), so the success text
 * says "dans quelques instants" and links to "Mes abonnements".
 *
 * The backend creates the PayPal order without a return URL of its own: for
 * PayPal to land here, this route must be configured as the return URL of the
 * PayPal app/order. Without it the traveler uses the "J'ai approuvé le paiement"
 * button of the pending panel, which runs the same capture. Not exercised
 * against a real PayPal account (documented in the README).
 */
@Component({
  selector: 'app-paypal-return',
  imports: [RouterLink, AlertComponent, CardComponent],
  template: `
    <div class="flex flex-col gap-6">
      <h1 class="font-display text-2xl font-bold text-ink">Retour de PayPal</h1>
      <app-card>
        @if (state() === 'capturing') {
          <p class="text-sm text-ink-dim" data-testid="capturing">
            Finalisation de votre paiement…
          </p>
        } @else if (state() === 'done') {
          <app-alert variant="success"
            >Paiement reçu. Votre inscription sera confirmée dans quelques instants.</app-alert
          >
        } @else {
          <app-alert variant="error">{{ error() }}</app-alert>
        }
        @if (state() !== 'capturing') {
          <p class="mt-3 text-sm">
            <a routerLink="/my-subscriptions" class="text-teal hover:underline"
              >Voir mes abonnements</a
            >
          </p>
        }
      </app-card>
    </div>
  `,
})
export class PaypalReturnComponent implements OnInit {
  private readonly paymentService = inject(PaymentService);
  private readonly store = inject(PendingPaymentStore);

  /** `token` query parameter (bound by withComponentInputBinding): the PayPal order id. */
  readonly token = input<string | undefined>(undefined);

  protected readonly state = signal<'capturing' | 'done' | 'error'>('capturing');
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    const orderId = this.token();
    if (!orderId) {
      this.state.set('error');
      this.error.set('Retour PayPal invalide : aucune commande indiquée.');
      return;
    }
    this.paymentService.capturePayPal(orderId).subscribe({
      next: (payment) => {
        if (payment.status === 'COMPLETED') {
          this.store.forget(payment.id);
          this.state.set('done');
        } else {
          this.state.set('error');
          this.error.set('PayPal n’a pas confirmé le paiement.');
        }
      },
      error: (err: unknown) => {
        this.state.set('error');
        this.error.set(captureErrorMessage(err));
      },
    });
  }
}
