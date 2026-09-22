import { DatePipe } from '@angular/common';
import { Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ButtonComponent } from '../../shared/ui/button/button.component';
import { RatingComponent } from '../../shared/ui/rating/rating.component';
import { Feedback } from './feedback.service';

/**
 * A list of feedbacks (manager dashboard, admin dashboard and feedback page,
 * per-travel view, "vos avis"). Purely presentational.
 *
 * The comment is another user's free text: it is rendered by interpolation
 * only (Angular escapes it) inside `whitespace-pre-line`, so line breaks show
 * but a `<script>` or `<b>` shows literally. There is deliberately no
 * `[innerHTML]` anywhere in this component.
 *
 * The backend does not paginate `GET /feedback` (or any other avis list): the
 * full list is always fetched in one call, but only `pageSize` items are
 * rendered at a time (client-side pagination) — Précédent/Suivant plus a
 * page indicator, hidden entirely when everything fits on one page. `items()`
 * changing (a fresh load, or a filter like AdminFeedbackComponent's rating
 * select) is reflected without extra wiring: the current page is clamped to
 * whatever the new item count allows.
 */
@Component({
  selector: 'app-feedback-list',
  imports: [DatePipe, RouterLink, RatingComponent, ButtonComponent],
  template: `
    <ul class="flex flex-col gap-3" data-testid="feedback-list">
      @for (item of pageItems(); track item.id) {
        <li
          class="flex flex-col gap-1 rounded-md border border-slate-200 p-3 text-sm dark:border-slate-700"
          data-testid="feedback-item"
        >
          <div class="flex flex-wrap items-center justify-between gap-2">
            <app-rating [value]="item.rating" />
            <span class="text-xs text-slate-500 dark:text-slate-400">{{
              item.createdAt | date: 'dd/MM/yyyy'
            }}</span>
          </div>
          @if (showDestination()) {
            <p class="font-medium text-slate-900 dark:text-slate-100">
              <a
                [routerLink]="[travelLink(), item.destinationId]"
                class="text-indigo-600 hover:underline dark:text-indigo-400"
                >{{ item.destinationName }}</a
              >
              <span class="font-normal text-slate-500 dark:text-slate-400">
                — {{ item.destinationCountry }}</span
              >
            </p>
          }
          @if (item.comment) {
            <p class="whitespace-pre-line break-words text-slate-700 dark:text-slate-200" data-testid="feedback-comment">{{
              item.comment
            }}</p>
          } @else {
            <p class="text-slate-500 dark:text-slate-400">Pas de commentaire.</p>
          }
          @if (showAuthor()) {
            <p class="break-all text-xs text-slate-500 dark:text-slate-400">
              Voyageur : {{ item.travelerId }}
            </p>
          }
        </li>
      } @empty {
        <li class="text-sm text-slate-500 dark:text-slate-400" data-testid="feedback-empty">
          {{ emptyMessage() }}
        </li>
      }
    </ul>

    @if (totalPages() > 1) {
      <div class="mt-3 flex items-center justify-between gap-3 text-sm" data-testid="feedback-pagination">
        <app-button variant="secondary" [disabled]="currentPage() === 1" (clicked)="previousPage()">
          Précédent
        </app-button>
        <span class="text-slate-600 dark:text-slate-300" data-testid="feedback-page-info">
          Page {{ currentPage() }} sur {{ totalPages() }}
        </span>
        <app-button
          variant="secondary"
          [disabled]="currentPage() === totalPages()"
          (clicked)="nextPage()"
        >
          Suivant
        </app-button>
      </div>
    }
  `,
})
export class FeedbackListComponent {
  readonly items = input.required<readonly Feedback[]>();
  readonly emptyMessage = input('Aucun avis pour le moment.');
  /** Show the travel name (off on a per-travel list, where it is the page title). */
  readonly showDestination = input(true);
  /** Show the author id — managers and admins only, an id and never an identity. */
  readonly showAuthor = input(false);
  /** Base route of the travel link (`/travels` for everyone). */
  readonly travelLink = input('/travels');
  /** Items per page (client-side pagination — the backend does not paginate this list). */
  readonly pageSize = input(10);

  private readonly page = signal(1);

  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.items().length / this.pageSize())));
  /** The raw `page` signal clamped to what the current item count allows. */
  protected readonly currentPage = computed(() => Math.min(this.page(), this.totalPages()));
  protected readonly pageItems = computed(() => {
    const start = (this.currentPage() - 1) * this.pageSize();
    return this.items().slice(start, start + this.pageSize());
  });

  protected previousPage(): void {
    this.page.set(Math.max(1, this.currentPage() - 1));
  }

  protected nextPage(): void {
    this.page.set(Math.min(this.totalPages(), this.currentPage() + 1));
  }
}
