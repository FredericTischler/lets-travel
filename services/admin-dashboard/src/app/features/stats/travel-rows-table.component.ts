import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { TRAVEL_STATUS_LABELS, formatMoneyMap } from '../../shared/format';
import { BadgeComponent, BadgeTone } from '../../shared/ui/badge/badge.component';
import { RatingComponent } from '../../shared/ui/rating/rating.component';
import { TravelRow } from './stats.service';

const TONES: Record<string, BadgeTone> = { UPCOMING: 'info', ONGOING: 'success', PAST: 'neutral' };

/**
 * Table of dashboard travel rows (manager dashboard, admin top lists and travel
 * history): dates, status, subscribers / capacity, rating and income. Income
 * `null` (payment-service unreachable) reads "indisponible", never `0`.
 * Travel names are free text and are only interpolated.
 */
@Component({
  selector: 'app-travel-rows-table',
  imports: [DatePipe, RouterLink, BadgeComponent, RatingComponent],
  template: `
    <div class="table-shell">
      <table class="table-base">
        <caption class="sr-only">
          {{ caption() }}
        </caption>
        <thead>
          <tr class="table-head-row">
            <th class="table-head-cell" scope="col">Voyage</th>
            <th class="table-head-cell" scope="col">Dates</th>
            <th class="table-head-cell" scope="col">Statut</th>
            <th class="table-head-cell" scope="col">Abonnés</th>
            <th class="table-head-cell" scope="col">Note</th>
            <th class="table-head-cell" scope="col">Revenu</th>
            @if (feedbackLink()) {
              <th class="table-head-cell" scope="col">Avis</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track row.destinationId) {
            <tr class="table-row" data-testid="travel-row">
              <td class="table-cell">
                <a
                  [routerLink]="['/travels', row.destinationId]"
                  class="font-medium text-indigo-600 hover:underline dark:text-indigo-400"
                  >{{ row.name }}</a
                >
                <span class="text-slate-500 dark:text-slate-400"> — {{ row.country }}</span>
              </td>
              <td class="table-cell whitespace-nowrap">
                {{ row.startDate | date: 'dd/MM/yyyy' }} → {{ row.endDate | date: 'dd/MM/yyyy' }}
              </td>
              <td class="table-cell">
                <app-badge [tone]="tone(row.status)">{{ statusLabel(row.status) }}</app-badge>
              </td>
              <td class="table-cell whitespace-nowrap tabular-nums">
                {{ row.subscribers }}{{ row.capacity !== null ? ' / ' + row.capacity : '' }}
              </td>
              <td class="table-cell whitespace-nowrap">
                <app-rating [value]="row.averageRating" />
                <span class="text-xs text-slate-500 dark:text-slate-400"> ({{ row.feedbackCount }})</span>
              </td>
              <td class="table-cell whitespace-nowrap tabular-nums" data-testid="row-income">
                {{ row.income === null ? 'indisponible' : money(row.income) }}
              </td>
              @if (feedbackLink()) {
                <td class="table-cell">
                  <a
                    [routerLink]="[feedbackLink(), row.destinationId, 'feedback']"
                    class="text-indigo-600 hover:underline dark:text-indigo-400"
                    >Voir les avis</a
                  >
                </td>
              }
            </tr>
          } @empty {
            <tr>
              <td class="table-empty-cell" colspan="7">{{ emptyMessage() }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class TravelRowsTableComponent {
  readonly rows = input.required<readonly TravelRow[]>();
  readonly caption = input('Voyages');
  readonly emptyMessage = input('Aucun voyage.');
  /** Base route of the per-travel feedback page (`/manager/travels`); `null` hides the column. */
  readonly feedbackLink = input<string | null>(null);

  protected readonly money = formatMoneyMap;

  protected statusLabel(status: string): string {
    return TRAVEL_STATUS_LABELS[status] ?? status;
  }

  protected tone(status: string): BadgeTone {
    return TONES[status] ?? 'neutral';
  }
}
