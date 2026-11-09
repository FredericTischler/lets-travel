import { Component, computed, inject, input } from '@angular/core';

import { TranslateService } from '../../i18n/translate.service';

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
      <span class="text-ink-dim">—</span>
    } @else {
      <span class="inline-flex items-center gap-1 whitespace-nowrap font-mono">
        <span class="text-amber" aria-hidden="true">{{ stars() }}</span>
        <span class="text-ink" data-testid="rating-text">{{ text() }}</span>
      </span>
    }
  `,
})
export class RatingComponent {
  /** The rating (an integer for one feedback, a mean for an aggregate), `null` if none. */
  readonly value = input<number | null>(null);

  private readonly translateService = inject(TranslateService);

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
    const decimal = this.translateService.locale() === 'en' ? shown : shown.replace('.', ',');
    return this.translateService.translate('{{value}} sur 5', { value: decimal });
  });
}
