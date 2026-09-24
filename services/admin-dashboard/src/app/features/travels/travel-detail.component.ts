import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { extractErrorMessage } from '../../shared/http-error';
import { TranslatePipe } from '../../shared/i18n/translate.pipe';
import { TranslateService } from '../../shared/i18n/translate.service';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { Destination, DestinationService } from '../destinations/destination.service';
import { FeedbackFormComponent } from '../feedback/feedback-form.component';
import { FeedbackListComponent } from '../feedback/feedback-list.component';
import { Feedback, FeedbackService } from '../feedback/feedback.service';
import { PAYMENT_PROVIDER_LABELS, PaymentProvider } from '../payments/payment.service';
import { REPORT_REASON_MAX_LENGTH, ReportService } from '../reports/report.service';
import {
  CANCELLATION_CUTOFF_DAYS,
  cancellationDeadline,
  hasStarted,
  isPastCancellationCutoff,
} from '../subscriptions/cancellation-rules';
import { currentSubscription } from '../subscriptions/payment-rules';
import { PendingPaymentComponent } from '../subscriptions/pending-payment.component';
import {
  PaymentCheckout,
  Subscription,
  SubscriptionService,
} from '../subscriptions/subscription.service';

/** What the pending-payment panel needs to know about a PENDING_PAYMENT subscription. */
export interface PendingInfo {
  paymentId: string | null;
  expiresAt: string | null;
  amount: number | null;
  currency: string | null;
  /** Only known right after the subscribe call. */
  checkout: PaymentCheckout | null;
}

/**
 * Detail of one travel (a `Destination`): dates, price, capacity, activities,
 * accommodations, plus the traveler's actions — subscribe (choosing how to pay
 * when the travel has a price) / unsubscribe, finish a pending payment, give
 * feedback once the travel is over, and report or open the page of the organiser.
 *
 * Whether the caller is subscribed is read from their own history
 * (GET /travelers/me/subscriptions): an ACTIVE row, or a PENDING_PAYMENT one
 * still inside its deadline (shown with the payment panel). The 3-day
 * cancellation cutoff is announced up front, but the button stays usable and the
 * backend remains the authority: its 409 is turned into an explicit message.
 * Feedback is offered only to an ACTIVE participant of an ended travel who has
 * not rated it yet (the backend enforces the same rules).
 *
 * Every free-text value (destination names, the report reason typed here, the
 * feedback comment) is rendered by interpolation only — no HTML binding.
 */
@Component({
  selector: 'app-travel-detail',
  imports: [
    FormsModule,
    RouterLink,
    DecimalPipe,
    AlertComponent,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    FeedbackFormComponent,
    FeedbackListComponent,
    PendingPaymentComponent,
    TranslatePipe,
  ],
  templateUrl: './travel-detail.component.html',
})
export class TravelDetailComponent implements OnInit {
  private readonly destinationService = inject(DestinationService);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly feedbackService = inject(FeedbackService);
  private readonly reportService = inject(ReportService);
  private readonly authService = inject(AuthService);
  private readonly translateService = inject(TranslateService);

  /** Route param `:id` (bound through `withComponentInputBinding`). */
  readonly id = input.required<string>();

  protected readonly travel = signal<Destination | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  /** True while the caller holds an ACTIVE subscription. */
  protected readonly subscribed = signal(false);
  /** Set while the caller holds a PENDING_PAYMENT subscription still inside its deadline. */
  protected readonly pending = signal<PendingInfo | null>(null);
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionSuccess = signal<string | null>(null);

  // Travel Buddies (docs/lets-travel-architecture-decisions.md §12): opt-in,
  // off by default. There is no endpoint to read the caller's own current
  // flag back, so `buddyVisible` only reflects a toggle made in this session
  // — it resets to "off" on reload even if the backend still has it set from
  // a previous visit (a documented rough edge, not a bug: the buddy list
  // itself is always read fresh from the backend, only the switch's initial
  // position is approximate).
  protected readonly buddyVisible = signal(false);
  protected readonly buddyVisibleBusy = signal(false);
  protected readonly buddies = signal<string[]>([]);
  protected readonly buddiesLoading = signal(false);

  // Payment choice (paid travels only).
  protected readonly providers: readonly PaymentProvider[] = ['PAYPAL', 'STRIPE', 'MANUAL'];
  protected readonly providerLabels = PAYMENT_PROVIDER_LABELS;
  protected readonly provider = signal<PaymentProvider>('PAYPAL');
  protected readonly priced = computed(() => (this.travel()?.price ?? 0) > 0);

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

  // Feedback (participants of an ended travel).
  private readonly today = new Date().toLocaleDateString('sv-SE');
  /** `endDate` strictly before today, the backend's definition of "ended". */
  protected readonly ended = computed(() => {
    const travel = this.travel();
    return travel !== null && travel.endDate < this.today;
  });
  protected readonly myFeedback = signal<Feedback | null>(null);
  protected readonly feedbackLoaded = signal(false);
  protected readonly canGiveFeedback = computed(
    () => this.subscribed() && this.ended() && this.feedbackLoaded() && this.myFeedback() === null,
  );

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
        if (this.ended()) {
          this.loadMyFeedback();
        }
      },
      error: (err: unknown) => {
        const notFound = err instanceof HttpErrorResponse && err.status === 404;
        this.loadError.set(
          notFound ? 'Ce voyage est introuvable.' : extractErrorMessage(err, 'Impossible de charger ce voyage.'),
        );
        this.loading.set(false);
      },
    });

    this.loadHistory();
  }

  /** (Re)reads the caller's history to know their current subscription state on this travel. */
  protected loadHistory(): void {
    this.subscriptionService.mine().subscribe({
      next: (rows) => {
        const current = currentSubscription(rows, this.id());
        const isActive = current?.status === 'ACTIVE';
        this.subscribed.set(isActive);
        if (isActive) {
          this.loadBuddies();
        }
        if (current?.status === 'PENDING_PAYMENT') {
          const price = this.travel()?.price ?? null;
          this.pending.set({
            paymentId: current.paymentId ?? null,
            expiresAt: current.expiresAt ?? null,
            amount: price,
            currency: 'EUR',
            checkout: null,
          });
        } else {
          this.pending.set(null);
        }
      },
      // Non-fatal: the page stays usable, the backend re-checks on every action.
      error: () => this.subscribed.set(false),
    });
  }

  subscribe(): void {
    this.startAction();
    const request = this.priced() ? { provider: this.provider() } : undefined;
    this.subscriptionService.subscribe(this.id(), request).subscribe({
      next: (subscription) => {
        this.busy.set(false);
        if (subscription.status === 'PENDING_PAYMENT') {
          this.applyPending(subscription);
        } else {
          this.subscribed.set(true);
          this.actionSuccess.set('Vous êtes inscrit à ce voyage.');
          if (this.ended()) {
            this.loadMyFeedback();
          }
        }
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.actionError.set(subscribeErrorMessage(err));
      },
    });
  }

  private applyPending(subscription: Subscription): void {
    this.pending.set({
      paymentId: subscription.payment?.paymentId ?? subscription.paymentId ?? null,
      expiresAt: subscription.expiresAt ?? null,
      amount: subscription.amount ?? this.travel()?.price ?? null,
      currency: subscription.currency ?? 'EUR',
      checkout: subscription.payment ?? null,
    });
    this.actionSuccess.set(
      'Réservation enregistrée : votre place est retenue, il reste à régler le paiement.',
    );
  }

  unsubscribe(): void {
    if (!confirm(this.translateService.translate('Annuler votre inscription à ce voyage ?'))) {
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

  /** Cancels an unpaid reservation (always allowed, even inside the 3-day cutoff). */
  cancelPending(): void {
    if (!confirm(this.translateService.translate('Annuler cette réservation en attente de paiement ?'))) {
      return;
    }
    this.startAction();
    this.subscriptionService.unsubscribe(this.id()).subscribe({
      next: () => {
        this.busy.set(false);
        this.pending.set(null);
        this.actionSuccess.set('Votre réservation a été annulée.');
      },
      error: (err: unknown) => {
        this.busy.set(false);
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.pending.set(null);
          this.loadHistory();
        }
        this.actionError.set(extractErrorMessage(err, 'Impossible d’annuler cette réservation.'));
      },
    });
  }

  /** The panel saw the payment complete: the subscription is (about to be) ACTIVE. */
  protected onPaymentSettled(): void {
    this.actionSuccess.set('Paiement reçu. Votre inscription est confirmée.');
    this.loadHistory();
  }

  /** Travel Buddies: the other opted-in travelers live on this destination. */
  protected loadBuddies(): void {
    this.buddiesLoading.set(true);
    this.subscriptionService.buddies(this.id()).subscribe({
      next: (rows) => {
        this.buddies.set(rows.map((r) => r.travelerId));
        this.buddiesLoading.set(false);
      },
      // Non-fatal: the opt-in toggle stays usable even if the list fails to load.
      error: () => this.buddiesLoading.set(false),
    });
  }

  /** Flip the caller's own "visible to other travelers here" flag. */
  protected toggleBuddyVisible(): void {
    const next = !this.buddyVisible();
    this.buddyVisibleBusy.set(true);
    this.subscriptionService.setBuddyVisible(this.id(), next).subscribe({
      next: () => {
        this.buddyVisible.set(next);
        this.buddyVisibleBusy.set(false);
        this.loadBuddies();
      },
      error: (err: unknown) => {
        this.buddyVisibleBusy.set(false);
        this.actionError.set(extractErrorMessage(err, 'Impossible de mettre à jour votre visibilité.'));
      },
    });
  }

  /** A short, non-identifying label for a buddy's UUID — never a name or email. */
  protected buddyLabel(travelerId: string): string {
    return 'Voyageur ' + travelerId.slice(-4).toUpperCase();
  }

  protected onFeedbackSaved(feedback: Feedback): void {
    this.myFeedback.set(feedback);
    this.actionSuccess.set('Merci, votre avis a été enregistré.');
  }

  private loadMyFeedback(): void {
    this.feedbackService.mine().subscribe({
      next: (rows) => {
        this.myFeedback.set(rows.find((f) => f.destinationId === this.id()) ?? null);
        this.feedbackLoaded.set(true);
      },
      // Non-fatal: without it the form is simply not offered (the backend would say 409 anyway).
      error: () => this.feedbackLoaded.set(false),
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

/** Message for each refusal of the subscribe call (SubscriptionController#subscribe). */
export function subscribeErrorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 409) {
      return 'Inscription impossible : ce voyage a déjà commencé, il est complet, ou vous y êtes déjà inscrit (ou en attente de paiement).';
    }
    if (err.status === 502) {
      return 'Le service de paiement est momentanément indisponible : rien n’a été réservé, vous pouvez réessayer.';
    }
  }
  return extractErrorMessage(err, 'Impossible de vous inscrire à ce voyage.');
}
