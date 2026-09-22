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
    <div class="flex h-full flex-col gap-1 border-t-2 border-line-strong bg-surface p-4">
      <p class="font-mono text-xs tracking-wide text-ink-dim uppercase">{{ label() }}</p>
      <p
        class="font-mono text-xl font-semibold break-words tabular-nums text-ink sm:text-3xl"
        data-testid="stat-value"
      >
        {{ value() }}
      </p>
      @if (hint()) {
        <p class="text-xs text-ink-dim" data-testid="stat-hint">{{ hint() }}</p>
      }
    </div>
  `,
})
export class StatTileComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly hint = input<string | null>(null);
}
