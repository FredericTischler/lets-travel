import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { extractErrorMessage } from '../../shared/http-error';
import { Page } from '../../shared/pagination';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { PaginatorComponent } from '../../shared/ui/paginator/paginator.component';
import { RatingComponent } from '../../shared/ui/rating/rating.component';
import { FeedbackListComponent } from './feedback-list.component';
import { Feedback, FeedbackService } from './feedback.service';

const PAGE_SIZE = 20;

/**
 * Global feedback list of the admin (`GET /feedback`): every avis on every
 * active travel, newest first, filterable by rating — the quality-control view
 * of the subject ("feedbacks to assess user satisfaction"). Comments are
 * interpolated only.
 *
 * Paginated server-side (`totalElements`/`totalPages` from the backend's
 * `Page<Feedback>`) — the rating filter and "note moyenne" summary only apply
 * to the currently loaded page, not the whole platform: there is no
 * server-side filter-by-rating endpoint, and computing a true platform-wide
 * average without loading everything would need a dedicated aggregate
 * endpoint, out of scope for adding pagination.
 */
@Component({
  selector: 'app-admin-feedback',
  imports: [
    FormsModule,
    AlertComponent,
    CardComponent,
    PaginatorComponent,
    RatingComponent,
    FeedbackListComponent,
  ],
  template: `
    <div class="flex flex-col gap-6">
      <h1 class="font-display text-2xl font-bold text-ink">Avis des voyageurs</h1>

      @if (loading()) {
        <p class="text-sm text-ink-dim">Chargement…</p>
      } @else if (error()) {
        <app-alert variant="error">{{ error() }}</app-alert>
      } @else if (page(); as p) {
        <app-card>
          <div class="flex flex-wrap items-center justify-between gap-3 text-sm">
            <p class="text-ink" data-testid="summary">
              {{ p.totalElements }} avis au total · note moyenne de cette page :
              <app-rating [value]="pageAverage()" />
            </p>
            <div class="flex items-center gap-2">
              <label for="ratingFilter" class="text-ink">Note (page affichée)</label>
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
          <app-paginator
            class="mt-3 block"
            [page]="p.page"
            [totalPages]="p.totalPages"
            [totalElements]="p.totalElements"
            [disabled]="loading()"
            (pageChange)="load($event)"
          />
        </app-card>
      }
    </div>
  `,
})
export class AdminFeedbackComponent implements OnInit {
  private readonly feedbackService = inject(FeedbackService);

  protected readonly page = signal<Page<Feedback> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** 0 = every rating. */
  protected readonly filter = signal(0);
  protected readonly ratings = [5, 4, 3, 2, 1];

  protected readonly visible = computed(() => {
    const rows = this.page()?.content ?? [];
    const rating = Number(this.filter());
    return rows.filter((f) => rating === 0 || f.rating === rating);
  });
  protected readonly pageAverage = computed(() => {
    const rows = this.page()?.content ?? [];
    return rows.length === 0 ? null : rows.reduce((sum, f) => sum + f.rating, 0) / rows.length;
  });

  ngOnInit(): void {
    this.load(0);
  }

  protected load(pageIndex: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.feedbackService.all(pageIndex, PAGE_SIZE).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger les avis.'));
        this.loading.set(false);
      },
    });
  }
}
