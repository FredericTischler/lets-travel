import { Component, computed, input, signal } from '@angular/core';

/** One column of {@link BarChartComponent}. */
export interface BarChartPoint {
  /** Short x-axis label (`sept. 2026`). */
  label: string;
  value: number;
  /** Optional extra line of the tooltip / table (e.g. the other currencies). */
  detail?: string | null;
}

/**
 * Rounds `max` up to a "nice" axis maximum (1, 2, 2.5, 5 or 10 × 10^n) so the
 * gridline labels read 0 / 1 000 / 2 000 rather than 0 / 1 137 / 2 274.
 * A non-positive or non-finite maximum gives 1 (an empty chart still needs an axis).
 */
export function niceMax(max: number): number {
  if (!Number.isFinite(max) || max <= 0) {
    return 1;
  }
  const magnitude = Math.pow(10, Math.floor(Math.log10(max)));
  const normalized = max / magnitude;
  const step = [1, 2, 2.5, 5, 10].find((candidate) => normalized <= candidate) ?? 10;
  return step * magnitude;
}

/**
 * A single-series column chart in plain HTML/CSS (no chart library — the
 * subject asks students to justify every package, and this is 100 lines).
 *
 * Follows the dataviz rules: one series in one colour (`--viz-series-1`, stepped
 * for light and dark by styles.css), thin columns capped at 24 px with a 4 px
 * rounded top and a square base, hairline recessive gridlines, a value at the
 * tip only of the tallest column (the axis and the tooltip carry the rest), a
 * tooltip on hover *and* keyboard focus, and a table twin under a disclosure so
 * no value is reachable only by hovering. Text uses the slate text classes,
 * never the series colour.
 */
@Component({
  selector: 'app-bar-chart',
  templateUrl: './bar-chart.component.html',
})
export class BarChartComponent {
  /** Accessible name of the chart (also its table caption). */
  readonly title = input.required<string>();
  readonly data = input.required<readonly BarChartPoint[]>();
  /** Formats a value for the axis, the tip label, the tooltip and the table. */
  readonly format = input<(value: number) => string>((value) => String(value));
  /** Header of the value column of the table twin. */
  readonly valueLabel = input('Valeur');
  readonly emptyMessage = input('Aucune donnée sur la période.');

  protected readonly active = signal<number | null>(null);

  protected readonly axisMax = computed(() => niceMax(Math.max(0, ...this.data().map((p) => p.value))));
  protected readonly ticks = computed(() => {
    const max = this.axisMax();
    return [max, max / 2, 0];
  });
  protected readonly hasData = computed(() => this.data().some((point) => point.value > 0));
  protected readonly peakIndex = computed(() => {
    const values = this.data().map((p) => p.value);
    const peak = Math.max(...values);
    return peak > 0 ? values.indexOf(peak) : -1;
  });

  /** Column height in percent of the plot (a zero value has no bar at all). */
  protected heightPercent(value: number): number {
    return value <= 0 ? 0 : Math.max(2, (value / this.axisMax()) * 100);
  }

  /** With many columns only every second x label is printed, so none collide. */
  protected showLabel(index: number): boolean {
    return this.data().length <= 12 || index % 2 === 0;
  }

  /** Tooltip position: centred on its column, kept inside the plot at the edges. */
  protected tooltipLeft(index: number): number {
    const centre = ((index + 0.5) / this.data().length) * 100;
    return Math.min(85, Math.max(15, centre));
  }

  protected pointLabel(point: BarChartPoint): string {
    const detail = point.detail ? ` (${point.detail})` : '';
    return `${point.label} : ${this.format()(point.value)}${detail}`;
  }
}
