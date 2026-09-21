import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

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
 */
@Component({
  selector: 'app-feedback-list',
  imports: [DatePipe, RouterLink, RatingComponent],
  template: `
    <ul class="flex flex-col gap-3" data-testid="feedback-list">
      @for (item of items(); track item.id) {
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
}
