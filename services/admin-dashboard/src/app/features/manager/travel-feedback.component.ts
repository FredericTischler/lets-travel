import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';

import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { RatingComponent } from '../../shared/ui/rating/rating.component';
import { Destination, DestinationService } from '../destinations/destination.service';
import { FeedbackListComponent } from '../feedback/feedback-list.component';
import { Feedback, FeedbackService } from '../feedback/feedback.service';

/**
 * Feedback received by one of the manager's travels
 * (`GET /destinations/{id}/feedback`): the owning manager or an admin, for
 * quality control — another manager gets a 403, shown as such. The author of an
 * avis is only an id (identities stay in identity-service). Comments are
 * interpolated, never bound as HTML.
 */
@Component({
  selector: 'app-travel-feedback',
  imports: [RouterLink, AlertComponent, CardComponent, RatingComponent, FeedbackListComponent],
  template: `
    <div class="flex flex-col gap-6">
      <a
        routerLink="/manager/travels"
        class="text-sm font-medium text-teal hover:underline"
        >← Retour à mes voyages</a
      >
      <h1 class="font-display text-2xl font-bold text-ink">
        Avis@if (travel(); as t) {
          <span class="font-normal"> — {{ t.name }}</span>
        }
      </h1>

      @if (loading()) {
        <p class="text-sm text-ink-dim">Chargement…</p>
      } @else if (error()) {
        <app-alert variant="error">{{ error() }}</app-alert>
      } @else {
        <app-card title="Synthèse">
          <p class="text-sm text-ink" data-testid="summary">
            {{ items().length }} avis · note moyenne :
            <app-rating [value]="average()" />
          </p>
        </app-card>
        <app-card title="Tous les avis">
          <app-feedback-list
            [items]="items()"
            [showDestination]="false"
            [showAuthor]="true"
            emptyMessage="Ce voyage n'a reçu aucun avis pour l'instant."
          />
        </app-card>
      }
    </div>
  `,
})
export class TravelFeedbackComponent implements OnInit {
  private readonly feedbackService = inject(FeedbackService);
  private readonly destinationService = inject(DestinationService);

  /** Route param `:id` (bound through `withComponentInputBinding`). */
  readonly id = input.required<string>();

  protected readonly travel = signal<Destination | null>(null);
  protected readonly items = signal<Feedback[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly average = computed(() => {
    const rows = this.items();
    return rows.length === 0 ? null : rows.reduce((sum, f) => sum + f.rating, 0) / rows.length;
  });

  ngOnInit(): void {
    forkJoin({
      feedback: this.feedbackService.forDestination(this.id()),
      // The title is a nicety: a failure must not hide the feedback.
      travel: this.destinationService.get(this.id()).pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ feedback, travel }) => {
        this.items.set(feedback);
        this.travel.set(travel);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 403
            ? 'Vous ne pouvez consulter que les avis de vos propres voyages.'
            : extractErrorMessage(err, 'Impossible de charger les avis de ce voyage.'),
        );
        this.loading.set(false);
      },
    });
  }
}
