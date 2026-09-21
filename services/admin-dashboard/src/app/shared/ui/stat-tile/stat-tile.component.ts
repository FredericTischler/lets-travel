import { Component, input } from '@angular/core';

/**
 * A KPI tile of the dashboards: sentence-case label, one big value (default
 * proportional figures — `tabular-nums` is reserved for table columns) and an
 * optional hint line (a caveat, a sub-figure). No chart: a single number *is*
 * the figure.
 *
 * `value` is a ready-to-display string so the caller decides the unit and the
 * "no data" rendering (`—`), rather than the tile guessing.
 */
@Component({
  selector: 'app-stat-tile',
  template: `
    <div
      class="flex h-full flex-col gap-1 rounded-lg border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800/50"
    >
      <p class="text-sm text-slate-600 dark:text-slate-300">{{ label() }}</p>
      <p
        class="break-words text-xl font-semibold text-slate-900 sm:text-3xl dark:text-slate-100"
        data-testid="stat-value"
      >
        {{ value() }}
      </p>
      @if (hint()) {
        <p class="text-xs text-slate-500 dark:text-slate-400" data-testid="stat-hint">{{ hint() }}</p>
      }
    </div>
  `,
})
export class StatTileComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly hint = input<string | null>(null);
}
