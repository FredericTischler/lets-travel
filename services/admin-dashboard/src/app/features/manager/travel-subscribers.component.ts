import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { ROLES } from '../../core/auth/roles';
import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { Destination, DestinationService } from '../destinations/destination.service';
import { Subscription, SubscriptionService } from '../subscriptions/subscription.service';

/**
 * Subscribers of one travel, for its owning Travel Manager (or an admin):
 * GET /destinations/{id}/subscriptions, with a force-unsubscribe per active
 * subscriber (DELETE /destinations/{id}/subscriptions/{travelerId}, which the
 * backend does not subject to the 3-day cutoff).
 *
 * The backend only exposes each traveler's id — no email or profile is
 * available to a manager — so the id is what is shown.
 */
@Component({
  selector: 'app-travel-subscribers',
  imports: [RouterLink, DatePipe, AlertComponent, BadgeComponent, ButtonComponent, CardComponent],
  templateUrl: './travel-subscribers.component.html',
})
export class TravelSubscribersComponent implements OnInit {
  private readonly destinationService = inject(DestinationService);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly authService = inject(AuthService);

  /** Route param `:id`. */
  readonly id = input.required<string>();

  protected readonly travel = signal<Destination | null>(null);
  protected readonly subscriptions = signal<Subscription[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly removingId = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  /** Active subscribers first, then most recent subscription first. */
  protected readonly rows = computed(() =>
    [...this.subscriptions()].sort((a, b) => {
      if (a.status !== b.status) {
        return a.status === 'ACTIVE' ? -1 : 1;
      }
      return b.subscribedAt.localeCompare(a.subscribedAt);
    }),
  );
  protected readonly activeCount = computed(
    () => this.subscriptions().filter((s) => s.status === 'ACTIVE').length,
  );

  protected readonly backLink = computed(() =>
    this.authService.role() === ROLES.ADMIN ? '/destinations' : '/manager/travels',
  );

  ngOnInit(): void {
    this.destinationService.get(this.id()).subscribe({
      next: (travel) => this.travel.set(travel),
      // The title is decorative: the subscriber list below reports the real error.
      error: () => this.travel.set(null),
    });
    this.loadSubscribers();
  }

  private loadSubscribers(): void {
    this.subscriptionService.listForDestination(this.id()).subscribe({
      next: (subscriptions) => {
        this.subscriptions.set(subscriptions);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 403
            ? "Vous n'êtes pas l'organisateur de ce voyage."
            : extractErrorMessage(err, 'Impossible de charger les abonnés.'),
        );
        this.loading.set(false);
      },
    });
  }

  forceUnsubscribe(subscription: Subscription): void {
    if (!confirm(`Désinscrire le voyageur ${subscription.travelerId} de ce voyage ?`)) {
      return;
    }

    this.removingId.set(subscription.travelerId);
    this.actionError.set(null);
    this.subscriptionService.forceUnsubscribe(this.id(), subscription.travelerId).subscribe({
      next: () => {
        this.removingId.set(null);
        this.loadSubscribers();
      },
      error: (err: unknown) => {
        this.removingId.set(null);
        this.actionError.set(extractErrorMessage(err, 'Impossible de désinscrire ce voyageur.'));
        // A 404 means it was already cancelled elsewhere: refresh to show the truth.
        this.loadSubscribers();
      },
    });
  }
}
