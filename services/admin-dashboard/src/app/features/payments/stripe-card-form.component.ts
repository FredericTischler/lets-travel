import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, inject, input, output, signal } from '@angular/core';
import { Stripe, StripeCardElement, StripeElements } from '@stripe/stripe-js';

import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { StripeService } from './stripe.service';

/**
 * Stripe Elements "Card" form, mounted only by `PendingPaymentComponent` when
 * `environment.stripePublishableKey` is configured and a fresh `clientSecret`
 * is known (only returned once, right after the subscribe call — see
 * subscription.service.ts `PaymentCheckout`). `clientSecret` is held in memory
 * only (an `@Input`, never written to storage or displayed) for the lifetime
 * of this component.
 *
 * On submit, confirms the PaymentIntent client-side
 * (`stripe.confirmCardPayment`). A successful confirmation only means "Stripe
 * accepted the card": the subscription itself only turns `ACTIVE` once the
 * Stripe webhook reaches payment-service and it notifies travel-service
 * (docs/lets-travel-architecture-decisions.md §4) — this component only
 * reports "sent", the parent re-reads the payment to know the real outcome.
 */
@Component({
  selector: 'app-stripe-card-form',
  imports: [AlertComponent, ButtonComponent],
  templateUrl: './stripe-card-form.component.html',
})
export class StripeCardFormComponent implements AfterViewInit, OnDestroy {
  private readonly stripeService = inject(StripeService);

  readonly clientSecret = input.required<string>();

  /** Emitted once Stripe has accepted the card (not yet: the subscription is settled). */
  readonly paid = output<void>();

  // Not `static: true`: the container lives inside an `@else` block (a structural view,
  // like `*ngIf`), so it only resolves once that view has actually been created.
  @ViewChild('cardElement') private readonly cardElementRef?: ElementRef<HTMLDivElement>;

  protected readonly loading = signal(true);
  protected readonly cardReady = signal(false);
  protected readonly cardError = signal<string | null>(null);
  protected readonly paying = signal(false);
  protected readonly loadError = signal<string | null>(null);

  private stripe: Stripe | null = null;
  private elements: StripeElements | null = null;
  private card: StripeCardElement | null = null;

  ngAfterViewInit(): void {
    this.stripeService.getStripe().then((stripe) => {
      if (!stripe || !this.cardElementRef) {
        this.loadError.set("Le formulaire de paiement n'a pas pu être chargé.");
        this.loading.set(false);
        return;
      }

      this.stripe = stripe;
      this.elements = stripe.elements();
      this.card = this.elements.create('card');
      this.card.mount(this.cardElementRef.nativeElement);
      this.card.on('ready', () => {
        this.cardReady.set(true);
        this.loading.set(false);
      });
      this.card.on('change', (event) => this.cardError.set(event.error?.message ?? null));
    });
  }

  protected pay(): void {
    if (!this.stripe || !this.card || this.paying()) {
      return;
    }

    this.paying.set(true);
    this.cardError.set(null);

    this.stripe
      .confirmCardPayment(this.clientSecret(), { payment_method: { card: this.card } })
      .then((result) => {
        this.paying.set(false);
        if (result.error) {
          this.cardError.set(result.error.message ?? 'Le paiement a été refusé.');
          return;
        }
        this.paid.emit();
      });
  }

  ngOnDestroy(): void {
    this.card?.destroy();
  }
}
