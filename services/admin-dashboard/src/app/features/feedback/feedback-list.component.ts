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
          class="flex flex-col gap-1 border border-line-strong p-3 text-sm"
          data-testid="feedback-item"
        >
          <div class="flex flex-wrap items-center justify-between gap-2">
            <app-rating [value]="item.rating" />
            <span class="text-xs text-ink-dim">{{
              item.createdAt | date: 'dd/MM/yyyy'
            }}</span>
          </div>
          @if (showDestination()) {
            <p class="font-medium text-ink">
              <a
                [routerLink]="[travelLink(), item.destinationId]"
                class="text-teal hover:underline"
                >{{ item.destinationName }}</a
              >
              <span class="font-normal text-ink-dim">
                — {{ item.destinationCountry }}</span
              >
            </p>
          }
          @if (item.comment) {
            <p class="whitespace-pre-line break-words text-ink" data-testid="feedback-comment">{{
              item.comment
            }}</p>
          } @else {
            <p class="text-ink-dim">Pas de commentaire.</p>
          }
          @if (showAuthor()) {
            <p class="break-all text-xs text-ink-dim">
              Voyageur : {{ item.travelerId }}
            </p>
          }
        </li>
      } @empty {
        <li class="text-sm text-ink-dim" data-testid="feedback-empty">
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
