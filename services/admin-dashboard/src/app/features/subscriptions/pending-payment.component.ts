import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';

import { formatMoney } from '../../shared/format';
import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import {
  PAYMENT_PROVIDER_LABELS,
  Payment,
  PaymentProvider,
  PaymentService,
} from '../payments/payment.service';
import { StripeCardFormComponent } from '../payments/stripe-card-form.component';
import { StripeService } from '../payments/stripe.service';
import { PaymentCheckout } from './subscription.service';
import { PendingPaymentStore } from './pending-payment.store';
import { extractPayPalOrderId, isTrustedPayPalUrl, remainingTime } from './payment-rules';

/**
 * The "en attente de paiement" panel of a PENDING_PAYMENT subscription: what to
 * do to finish paying, per provider, and how long the seat is held.
 *
 * - **MANUAL**: an administrator confirms the payment (PATCH /payments/{id}/status)
 *   once the money has been received off-line; nothing to do here but wait.
 * - **PAYPAL**: redirect to the PayPal approval page, then capture the order
 *   (`POST /payments/paypal/{orderId}/capture`) — automatically on return
 *   (see PaypalReturnComponent) or with the "J'ai approuvé" button here.
 * - **STRIPE**: the backend creates the PaymentIntent and confirms the
 *   subscription through the Stripe webhook. Whether this front can take a
 *   card here depends on `environment.stripePublishableKey`: empty (the
 *   default — no real Stripe key exists in this project) means the panel
 *   shows the payment reference and status and says honestly that it cannot
 *   be paid from here; configured means a Stripe Elements card form
 *   (`StripeCardFormComponent`) is shown instead, using the `clientSecret`
 *   from `checkout()` (never stored, only held in memory for this component's
 *   lifetime — see its own doc comment). Not exercised against a real Stripe
 *   account either way.
 *
 * `checkout` is only known right after the subscribe call; later the provider
 * is read back from GET /payments/{id}. Cancelling is the parent's job
 * (`cancelRequested`), a settled payment is reported with `settled`.
 */
@Component({
  selector: 'app-pending-payment',
  imports: [DatePipe, AlertComponent, BadgeComponent, ButtonComponent, StripeCardFormComponent],
  templateUrl: './pending-payment.component.html',
})
export class PendingPaymentComponent implements OnInit {
  private readonly paymentService = inject(PaymentService);
  private readonly stripeService = inject(StripeService);
  private readonly store = inject(PendingPaymentStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly paymentId = input<string | null>(null);
  readonly expiresAt = input<string | null>(null);
  readonly amount = input<number | null>(null);
  readonly currency = input<string | null>(null);
  readonly checkout = input<PaymentCheckout | null>(null);

  readonly cancelRequested = output<void>();
  readonly settled = output<void>();

  protected readonly payment = signal<Payment | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  private readonly now = signal(new Date());

  protected readonly remaining = computed(() => remainingTime(this.expiresAt(), this.now()));

  protected readonly provider = computed<PaymentProvider | null>(
    () =>
      this.checkout()?.provider ??
      this.payment()?.provider ??
      (this.paymentId() ? this.store.get(this.paymentId()!)?.provider : null) ??
      null,
  );
  protected readonly providerLabel = computed(() => {
    const provider = this.provider();
    return provider ? PAYMENT_PROVIDER_LABELS[provider] : 'Mode de paiement inconnu';
  });

  protected readonly approveUrl = computed(() => {
    const id = this.paymentId();
    return this.checkout()?.approveUrl ?? (id ? this.store.get(id)?.approveUrl : null) ?? null;
  });
  protected readonly orderId = computed(() => {
    const id = this.paymentId();
    return (
      extractPayPalOrderId(this.approveUrl()) ??
      this.payment()?.externalReference ??
      (id ? this.store.get(id)?.orderId : null) ??
      null
    );
  });
  protected readonly amountLabel = computed(() => {
    const amount = this.amount();
    return amount === null ? null : formatMoney(amount, this.currency() ?? 'EUR');
  });
  protected readonly reference = computed(
    () => this.payment()?.externalReference ?? this.paymentId() ?? null,
  );

  /** Gates the Stripe Elements card form: unset by default (see StripeService). */
  protected readonly stripeConfigured = this.stripeService.isConfigured();
  /** Only known right after a fresh subscribe call — never stored (see class doc). */
  protected readonly clientSecret = computed(() => this.checkout()?.clientSecret ?? null);

  constructor() {
    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(new Date()));
  }

  ngOnInit(): void {
    const checkout = this.checkout();
    const id = this.paymentId();
    if (id && checkout?.provider === 'PAYPAL') {
      this.store.remember(id, {
        provider: 'PAYPAL',
        approveUrl: checkout.approveUrl,
        orderId: extractPayPalOrderId(checkout.approveUrl),
      });
    }
    this.refresh(true);
  }

  /** Re-reads the payment; a COMPLETED one means the subscription is about to turn ACTIVE. */
  refresh(silent = false): void {
    const id = this.paymentId();
    if (!id) {
      return;
    }
    this.paymentService.get(id).subscribe({
      next: (payment) => {
        this.payment.set(payment);
        if (payment.status === 'COMPLETED') {
          this.finish();
        } else if (payment.status === 'FAILED') {
          this.error.set('Ce paiement a échoué : la réservation va être annulée. Vous pouvez recommencer.');
        } else if (!silent) {
          this.info.set('Le paiement n’est pas encore confirmé.');
        }
      },
      // Non-fatal (e.g. not yours / gone): the panel keeps what it already knows.
      error: () => undefined,
    });
  }

  /** Sends the payer to PayPal (approval page), after remembering the order for the way back. */
  protected payWithPayPal(): void {
    const url = this.approveUrl();
    if (!isTrustedPayPalUrl(url)) {
      this.error.set('Lien de paiement PayPal indisponible ou non fiable : annulez la réservation et recommencez.');
      return;
    }
    this.navigate(url!);
  }

  /** Captures the approved PayPal order. */
  protected capture(): void {
    const orderId = this.orderId();
    if (!orderId) {
      this.error.set('Référence PayPal introuvable : annulez la réservation et recommencez.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.info.set(null);
    this.paymentService.capturePayPal(orderId).subscribe({
      next: (payment) => {
        this.busy.set(false);
        this.payment.set(payment);
        if (payment.status === 'COMPLETED') {
          this.finish();
        } else {
          this.error.set('PayPal n’a pas confirmé le paiement.');
        }
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(captureErrorMessage(err));
      },
    });
  }

  /** Stripe accepted the card: not settled yet (the webhook still has to reach the backend). */
  protected onStripePaid(): void {
    this.info.set('Paiement transmis à Stripe : confirmation en cours (cela peut prendre quelques instants).');
    this.refresh(true);
  }

  private finish(): void {
    const id = this.paymentId();
    if (id) {
      this.store.forget(id);
    }
    this.info.set('Paiement reçu. Votre inscription sera confirmée dans quelques instants.');
    this.settled.emit();
  }

  /** Wrapped so tests can observe the redirect without leaving the page. */
  protected navigate(url: string): void {
    window.location.assign(url);
  }
}

/** Message for each way a PayPal capture can be refused (PayPalPaymentController#capture). */
export function captureErrorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 409) {
      return 'Ce paiement a déjà été traité : actualisez la page pour voir l’état de votre inscription.';
    }
    if (err.status === 404) {
      return 'Commande PayPal introuvable.';
    }
    if (err.status === 502) {
      return 'PayPal a refusé la capture : le paiement n’a pas abouti. Annulez la réservation et recommencez.';
    }
  }
  return extractErrorMessage(err, 'Impossible de finaliser le paiement.');
}
