import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BadgeComponent, BadgeTone } from '../../shared/ui/badge/badge.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { remainingTime } from './payment-rules';
import { PendingPaymentComponent } from './pending-payment.component';
import { SubscriptionStatus, SubscriptionService, TravelerSubscription } from './subscription.service';

const STATUS_LABELS: Record<SubscriptionStatus, string> = {
  ACTIVE: 'Active',
  PENDING_PAYMENT: 'En attente de paiement',
  CANCELLED: 'Annulée',
  EXPIRED: 'Expirée',
};

const STATUS_TONES: Record<SubscriptionStatus, BadgeTone> = {
  ACTIVE: 'success',
  PENDING_PAYMENT: 'warning',
  CANCELLED: 'neutral',
  EXPIRED: 'neutral',
};

/**
 * "Mes abonnements": the caller's subscription history
 * (GET /travelers/me/subscriptions), split into upcoming and past travels
 * plus the number of cancellations — the "past participation" and
 * "subscription cancellations" figures of the subject's personal statistics.
 * Sorted newest travel first.
 */
@Component({
  selector: 'app-my-subscriptions',
  imports: [
    RouterLink,
    DatePipe,
    NgTemplateOutlet,
    AlertComponent,
    BadgeComponent,
    CardComponent,
    PendingPaymentComponent,
  ],
  templateUrl: './my-subscriptions.component.html',
})
export class MySubscriptionsComponent implements OnInit {
  private readonly subscriptionService = inject(SubscriptionService);

  protected readonly subscriptions = signal<TravelerSubscription[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // Local calendar day as YYYY-MM-DD (the format of `destinationStartDate`).
  private readonly today = new Date().toLocaleDateString('sv-SE');

  private readonly sorted = computed(() =>
    [...this.subscriptions()].sort((a, b) =>
      b.destinationStartDate.localeCompare(a.destinationStartDate),
    ),
  );

  protected readonly upcoming = computed(() =>
    this.sorted().filter((s) => s.status === 'ACTIVE' && s.destinationStartDate >= this.today),
  );
  /** Travels the traveler took part in (active subscription, start date behind us). */
  protected readonly past = computed(() =>
    this.sorted().filter((s) => s.status === 'ACTIVE' && s.destinationStartDate < this.today),
  );
  protected readonly cancelled = computed(() =>
    this.sorted().filter((s) => s.status === 'CANCELLED'),
  );
  /** Reservations still holding a seat: waiting for the payment, inside their deadline. */
  protected readonly pending = computed(() =>
    this.sorted().filter((s) => s.status === 'PENDING_PAYMENT' && !remainingTime(s.expiresAt).expired),
  );
  /** Unpaid reservations that ran out of time (EXPIRED, or PENDING_PAYMENT past its deadline). */
  protected readonly expired = computed(() =>
    this.sorted().filter(
      (s) => s.status === 'EXPIRED' || (s.status === 'PENDING_PAYMENT' && remainingTime(s.expiresAt).expired),
    ),
  );

  protected readonly actionError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.subscriptionService.mine().subscribe({
      next: (subscriptions) => {
        this.subscriptions.set(subscriptions);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger vos abonnements.'));
        this.loading.set(false);
      },
    });
  }

  /** Cancels an unpaid reservation (always allowed) and refreshes the history. */
  protected cancelPending(row: TravelerSubscription): void {
    if (!confirm(`Annuler la réservation en attente pour ${row.destinationName} ?`)) {
      return;
    }
    this.actionError.set(null);
    this.subscriptionService.unsubscribe(row.destinationId).subscribe({
      next: () => this.load(),
      error: (err: unknown) =>
        this.actionError.set(extractErrorMessage(err, 'Impossible d’annuler cette réservation.')),
    });
  }

  protected statusLabel(status: SubscriptionStatus): string {
    return STATUS_LABELS[status] ?? status;
  }

  protected statusTone(status: SubscriptionStatus): BadgeTone {
    return STATUS_TONES[status] ?? 'neutral';
  }
}
