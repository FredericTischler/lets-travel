import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BadgeComponent, BadgeTone } from '../../shared/ui/badge/badge.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { SubscriptionStatus, SubscriptionService, TravelerSubscription } from './subscription.service';

const STATUS_LABELS: Record<SubscriptionStatus, string> = {
  ACTIVE: 'Active',
  CANCELLED: 'Annulée',
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
  imports: [RouterLink, DatePipe, NgTemplateOutlet, AlertComponent, BadgeComponent, CardComponent],
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

  ngOnInit(): void {
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

  protected statusLabel(status: SubscriptionStatus): string {
    return STATUS_LABELS[status] ?? status;
  }

  protected statusTone(status: SubscriptionStatus): BadgeTone {
    return status === 'ACTIVE' ? 'success' : 'neutral';
  }
}
