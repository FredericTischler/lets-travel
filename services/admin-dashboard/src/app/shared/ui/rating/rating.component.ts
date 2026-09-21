import { Component, computed, input } from '@angular/core';

/**
 * Read-only star rating, 1..5. The glyphs are decorative (`aria-hidden`); the
 * accessible value is the text `4 sur 5`, so the rating never relies on the
 * colour or shape of the stars. A `null` value (no feedback yet) renders `—`
 * — not five empty stars, which would read as "rated 0".
 */
@Component({
  selector: 'app-rating',
  template: `
    @if (value() === null) {
      <span class="text-slate-500 dark:text-slate-400">—</span>
    } @else {
      <span class="inline-flex items-center gap-1 whitespace-nowrap">
        <span class="text-amber-600 dark:text-amber-400" aria-hidden="true">{{ stars() }}</span>
        <span class="text-slate-700 dark:text-slate-200" data-testid="rating-text">{{ text() }}</span>
      </span>
    }
  `,
})
export class RatingComponent {
  /** The rating (an integer for one feedback, a mean for an aggregate), `null` if none. */
  readonly value = input<number | null>(null);

  protected readonly stars = computed(() => {
    const value = this.value();
    if (value === null) {
      return '';
    }
    const filled = Math.max(0, Math.min(5, Math.round(value)));
    return '★'.repeat(filled) + '☆'.repeat(5 - filled);
  });

  protected readonly text = computed(() => {
    const value = this.value();
    if (value === null) {
      return '';
    }
    const shown = Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/\.?0+$/, '');
    return `${shown.replace('.', ',')} sur 5`;
  });
}
