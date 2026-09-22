import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { RatingComponent } from '../../shared/ui/rating/rating.component';
import { FeedbackListComponent } from './feedback-list.component';
import { Feedback, FeedbackService } from './feedback.service';

/**
 * Global feedback list of the admin (`GET /feedback`): every avis on every
 * active travel, newest first, filterable by rating — the quality-control view
 * of the subject ("feedbacks to assess user satisfaction"). The list is not
 * paginated by the backend (demo volume). Comments are interpolated only.
 */
@Component({
  selector: 'app-admin-feedback',
  imports: [FormsModule, AlertComponent, CardComponent, RatingComponent, FeedbackListComponent],
  template: `
    <div class="flex flex-col gap-6">
      <h1 class="font-display text-2xl font-bold text-ink">Avis des voyageurs</h1>

      @if (loading()) {
        <p class="text-sm text-ink-dim">Chargement…</p>
      } @else if (error()) {
        <app-alert variant="error">{{ error() }}</app-alert>
      } @else {
        <app-card>
          <div class="flex flex-wrap items-center justify-between gap-3 text-sm">
            <p class="text-ink" data-testid="summary">
              {{ all().length }} avis · note moyenne : <app-rating [value]="average()" />
            </p>
            <div class="flex items-center gap-2">
              <label for="ratingFilter" class="text-ink">Note</label>
              <select
                id="ratingFilter"
                name="ratingFilter"
                [ngModel]="filter()"
                (ngModelChange)="filter.set($event)"
                class="border-0 border-b-2 border-line-strong bg-transparent px-1 py-1 font-mono text-sm text-ink focus:border-amber focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber"
              >
                <option [ngValue]="0">Toutes</option>
                @for (value of ratings; track value) {
                  <option [ngValue]="value">{{ value }} sur 5</option>
                }
              </select>
            </div>
          </div>
        </app-card>

        <app-card title="Avis">
          <app-feedback-list
            [items]="visible()"
            [showAuthor]="true"
            emptyMessage="Aucun avis ne correspond."
          />
        </app-card>
      }
    </div>
  `,
})
export class AdminFeedbackComponent implements OnInit {
  private readonly feedbackService = inject(FeedbackService);

  protected readonly all = signal<Feedback[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** 0 = every rating. */
  protected readonly filter = signal(0);
  protected readonly ratings = [5, 4, 3, 2, 1];

  protected readonly visible = computed(() => {
    const rating = Number(this.filter());
    return this.all().filter((f) => rating === 0 || f.rating === rating);
  });
  protected readonly average = computed(() => {
    const rows = this.all();
    return rows.length === 0 ? null : rows.reduce((sum, f) => sum + f.rating, 0) / rows.length;
  });

  ngOnInit(): void {
    this.feedbackService.all().subscribe({
      next: (rows) => {
        this.all.set([...rows].sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger les avis.'));
        this.loading.set(false);
      },
    });
  }
}
