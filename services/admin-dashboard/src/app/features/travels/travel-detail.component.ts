import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { Destination, DestinationService } from '../destinations/destination.service';
import { REPORT_REASON_MAX_LENGTH, ReportService } from '../reports/report.service';
import {
  CANCELLATION_CUTOFF_DAYS,
  cancellationDeadline,
  hasStarted,
  isPastCancellationCutoff,
} from '../subscriptions/cancellation-rules';
import { SubscriptionService } from '../subscriptions/subscription.service';

/**
 * Detail of one travel (a `Destination`): dates, price, capacity, activities,
 * accommodations, plus the traveler's actions — subscribe / unsubscribe and
 * report the organiser.
 *
 * Whether the caller is already subscribed is read from their own history
 * (GET /travelers/me/subscriptions, an ACTIVE row for this destination).
 * The 3-day cancellation cutoff is announced up front, but the button stays
 * usable and the backend remains the authority: its 409 is turned into an
 * explicit message rather than a generic failure.
 *
 * Every free-text value (destination names, the report reason typed here) is
 * rendered by interpolation only — no HTML binding.
 */
@Component({
  selector: 'app-travel-detail',
  imports: [FormsModule, RouterLink, DecimalPipe, AlertComponent, ButtonComponent, CardComponent],
  templateUrl: './travel-detail.component.html',
})
export class TravelDetailComponent implements OnInit {
  private readonly destinationService = inject(DestinationService);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly reportService = inject(ReportService);
  private readonly authService = inject(AuthService);

  /** Route param `:id` (bound through `withComponentInputBinding`). */
  readonly id = input.required<string>();

  protected readonly travel = signal<Destination | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly subscribed = signal(false);
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionSuccess = signal<string | null>(null);

  protected readonly cutoffDays = CANCELLATION_CUTOFF_DAYS;
  protected readonly started = computed(() => {
    const travel = this.travel();
    return travel !== null && hasStarted(travel.startDate);
  });
  protected readonly pastCutoff = computed(() => {
    const travel = this.travel();
    return travel !== null && isPastCancellationCutoff(travel.startDate);
  });
  protected readonly deadline = computed(() => {
    const travel = this.travel();
    return travel === null ? null : cancellationDeadline(travel.startDate);
  });

  // Report-the-organiser state.
  protected readonly reasonMaxLength = REPORT_REASON_MAX_LENGTH;
  protected readonly reportOpen = signal(false);
  protected reportReason = '';
  protected readonly reporting = signal(false);
  protected readonly reportError = signal<string | null>(null);
  protected readonly reportSent = signal(false);
  protected readonly reportCount = signal<number | null>(null);

  /** A user cannot report themselves (the backend answers 400). */
  protected readonly canReportManager = computed(() => {
    const managerId = this.travel()?.managerId;
    return !!managerId && managerId !== this.authService.getCurrentUserId();
  });

  ngOnInit(): void {
    this.destinationService.get(this.id()).subscribe({
      next: (travel) => {
        this.travel.set(travel);
        this.loading.set(false);
        this.loadReportCount(travel);
      },
      error: (err: unknown) => {
        const notFound = err instanceof HttpErrorResponse && err.status === 404;
        this.loadError.set(
          notFound ? 'Ce voyage est introuvable.' : extractErrorMessage(err, 'Impossible de charger ce voyage.'),
        );
        this.loading.set(false);
      },
    });

    this.subscriptionService.mine().subscribe({
      next: (subscriptions) =>
        this.subscribed.set(
          subscriptions.some((s) => s.destinationId === this.id() && s.status === 'ACTIVE'),
        ),
      // Non-fatal: the page stays usable, the backend re-checks on every action.
      error: () => this.subscribed.set(false),
    });
  }

  subscribe(): void {
    this.startAction();
    this.subscriptionService.subscribe(this.id()).subscribe({
      next: () => {
        this.busy.set(false);
        this.subscribed.set(true);
        this.actionSuccess.set('Vous êtes inscrit à ce voyage.');
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.actionError.set(
          err instanceof HttpErrorResponse && err.status === 409
            ? 'Inscription impossible : ce voyage a déjà commencé ou vous y êtes déjà inscrit.'
            : extractErrorMessage(err, 'Impossible de vous inscrire à ce voyage.'),
        );
      },
    });
  }

  unsubscribe(): void {
    if (!confirm('Annuler votre inscription à ce voyage ?')) {
      return;
    }

    this.startAction();
    this.subscriptionService.unsubscribe(this.id()).subscribe({
      next: () => {
        this.busy.set(false);
        this.subscribed.set(false);
        this.actionSuccess.set('Votre inscription a été annulée.');
      },
      error: (err: unknown) => {
        this.busy.set(false);
        if (err instanceof HttpErrorResponse && err.status === 409) {
          this.actionError.set(
            `Désinscription refusée : il reste moins de ${this.cutoffDays} jours avant le départ. ` +
              `L'annulation n'était possible que jusqu'au ${this.deadline() ?? 'délai limite'}.`,
          );
        } else if (err instanceof HttpErrorResponse && err.status === 404) {
          this.subscribed.set(false);
          this.actionError.set('Aucune inscription active à annuler pour ce voyage.');
        } else {
          this.actionError.set(extractErrorMessage(err, 'Impossible d’annuler votre inscription.'));
        }
      },
    });
  }

  toggleReport(): void {
    this.reportOpen.update((open) => !open);
    this.reportError.set(null);
  }

  submitReport(): void {
    const managerId = this.travel()?.managerId;
    const reason = this.reportReason.trim();
    if (!managerId || reason === '') {
      return;
    }

    this.reporting.set(true);
    this.reportError.set(null);
    this.reportService.create(managerId, reason).subscribe({
      next: () => {
        this.reporting.set(false);
        this.reportSent.set(true);
        this.reportOpen.set(false);
        this.reportReason = '';
        this.loadReportCount(this.travel());
      },
      error: (err: unknown) => {
        this.reporting.set(false);
        this.reportError.set(extractErrorMessage(err, 'Impossible d’envoyer ce signalement.'));
      },
    });
  }

  private startAction(): void {
    this.busy.set(true);
    this.actionError.set(null);
    this.actionSuccess.set(null);
  }

  private loadReportCount(travel: Destination | null): void {
    if (!travel?.managerId) {
      return;
    }
    this.reportService.countFor(travel.managerId).subscribe({
      next: (count) => this.reportCount.set(count),
      // Purely informative: silently omitted when unavailable.
      error: () => this.reportCount.set(null),
    });
  }
}
