import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { StripeElements, StripePaymentElement } from '@stripe/stripe-js';
import { interval } from 'rxjs';

import { environment } from '../../../environments/environment';
import { formatMoney } from '../../shared/format';
import { extractErrorMessage } from '../../shared/http-error';
import { TranslatePipe } from '../../shared/i18n/translate.pipe';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import {
  PAYMENT_PROVIDER_LABELS,
  Payment,
  PaymentProvider,
  PaymentService,
} from '../payments/payment.service';
import { PaymentCheckout } from './subscription.service';
import { PendingPaymentStore } from './pending-payment.store';
import { StripeCheckoutService } from './stripe-checkout.service';
import { extractPayPalOrderId, isTrustedPayPalUrl, remainingTime } from './payment-rules';

/** How many times to re-poll GET /payments/{id} after a client-side Stripe confirmation. */
const STRIPE_SETTLE_POLL_ATTEMPTS = 10;
const STRIPE_SETTLE_POLL_DELAY_MS = 2000;

/**
 * The "en attente de paiement" panel of a PENDING_PAYMENT subscription: what to
 * do to finish paying, per provider, and how long the seat is held.
 *
 * - **MANUAL**: an administrator confirms the payment (PATCH /payments/{id}/status)
 *   once the money has been received off-line; nothing to do here but wait.
 * - **PAYPAL**: redirect to the PayPal approval page, then capture the order
 *   (`POST /payments/paypal/{orderId}/capture`) — automatically on return
 *   (see PaypalReturnComponent) or with the "J'ai approuvé" button here.
 * - **STRIPE**: the backend creates the PaymentIntent and returns its
 *   `clientSecret` once, right after subscribing; this panel uses Stripe.js
 *   (Payment Element) to collect the card and confirm that PaymentIntent
 *   client-side, then polls GET /payments/{id} until the webhook flips it to
 *   COMPLETED. `clientSecret` is deliberately never persisted (see
 *   PendingPaymentStore), so the card form only appears right after
 *   subscribing — reopening this panel later (e.g. from "Mes abonnements")
 *   shows an explicit "start over" message instead, same as an expired
 *   PayPal approval URL. Also degrades to an explicit "not configured"
 *   message when no publishable key is set (see environment.ts).
 *
 * `checkout` is only known right after the subscribe call; later the provider
 * is read back from GET /payments/{id}. Cancelling is the parent's job
 * (`cancelRequested`), a settled payment is reported with `settled`.
 */
@Component({
  selector: 'app-pending-payment',
  imports: [DatePipe, AlertComponent, BadgeComponent, ButtonComponent, TranslatePipe],
  templateUrl: './pending-payment.component.html',
})
export class PendingPaymentComponent implements OnInit, OnDestroy {
  private readonly paymentService = inject(PaymentService);
  private readonly store = inject(PendingPaymentStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stripeCheckout = inject(StripeCheckoutService);

  readonly paymentId = input<string | null>(null);
  readonly expiresAt = input<string | null>(null);
  readonly amount = input<number | null>(null);
  readonly currency = input<string | null>(null);
  readonly checkout = input<PaymentCheckout | null>(null);

  readonly cancelRequested = output<void>();
  readonly settled = output<void>();

  private readonly stripeMount = viewChild<ElementRef<HTMLDivElement>>('stripeMount');

  protected readonly payment = signal<Payment | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  private readonly now = signal(new Date());

  protected readonly stripeConfigured = environment.stripePublishableKey !== '';
  protected readonly stripeLoading = signal(false);
  protected readonly stripeReady = signal(false);
  protected readonly stripeSubmitting = signal(false);
  protected readonly stripeError = signal<string | null>(null);
  private stripeElements: StripeElements | null = null;
  private stripePaymentElement: StripePaymentElement | null = null;
  private stripeMounted = false;
  private destroyed = false;

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

  /** Only right after subscribing — see the class doc on why it is never persisted. */
  protected readonly stripeClientSecret = computed(() =>
    this.provider() === 'STRIPE' ? this.checkout()?.clientSecret ?? null : null,
  );

  constructor() {
    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(new Date()));

    effect(() => {
      const mount = this.stripeMount();
      const clientSecret = this.stripeClientSecret();
      if (mount && clientSecret && this.stripeConfigured && !this.stripeMounted && !this.remaining().expired) {
        this.stripeMounted = true;
        void this.mountStripeForm(mount.nativeElement, clientSecret);
      }
    });
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

  private async mountStripeForm(container: HTMLDivElement, clientSecret: string): Promise<void> {
    this.stripeLoading.set(true);
    const stripe = await this.stripeCheckout.load();
    if (!stripe) {
      this.stripeLoading.set(false);
      this.stripeError.set('Le paiement par carte n’est pas configuré sur ce site (clé Stripe manquante).');
      return;
    }
    this.stripeElements = stripe.elements({ clientSecret });
    this.stripePaymentElement = this.stripeElements.create('payment');
    this.stripePaymentElement.on('ready', () => {
      this.stripeLoading.set(false);
      this.stripeReady.set(true);
    });
    this.stripePaymentElement.mount(container);
  }

  /** Confirms the PaymentIntent from the mounted card form; the webhook then settles it server-side. */
  protected async payWithStripe(): Promise<void> {
    const stripe = await this.stripeCheckout.load();
    if (!stripe || !this.stripeElements) {
      return;
    }
    this.stripeSubmitting.set(true);
    this.stripeError.set(null);
    const { error, paymentIntent } = await stripe.confirmPayment({
      elements: this.stripeElements,
      confirmParams: { return_url: window.location.href },
      redirect: 'if_required',
    });
    this.stripeSubmitting.set(false);
    if (error) {
      this.stripeError.set(error.message ?? 'Le paiement a été refusé par Stripe.');
      return;
    }
    if (paymentIntent?.status === 'succeeded' || paymentIntent?.status === 'processing') {
      this.info.set('Paiement transmis à Stripe : confirmation en cours…');
      this.pollUntilSettled();
    }
  }

  /** The client-side confirmation above only tells Stripe; COMPLETED still comes from the webhook. */
  private pollUntilSettled(attempt = 0): void {
    if (attempt >= STRIPE_SETTLE_POLL_ATTEMPTS) {
      return;
    }
    setTimeout(() => {
      if (this.destroyed || this.payment()?.status === 'COMPLETED') {
        return;
      }
      this.refresh(true);
      if (this.payment()?.status !== 'COMPLETED') {
        this.pollUntilSettled(attempt + 1);
      }
    }, STRIPE_SETTLE_POLL_DELAY_MS);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.stripePaymentElement?.unmount();
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
