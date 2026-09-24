import { Pipe, PipeTransform, inject } from '@angular/core';

import { TranslateService } from './translate.service';

/**
 * `{{ 'French text' | translate }}` / `{{ 'Voici {{n}}' | translate: { n: 3 } }}`.
 *
 * A plain (non-`pure: false`) pipe: reading `TranslateService.locale()`
 * inside `transform()` (via `translate()`) is enough for Angular's signal
 * reactivity to re-run this binding when the locale changes — no impure
 * pipe / manual change detection needed, zoneless-safe.
 */
@Pipe({ name: 'translate' })
export class TranslatePipe implements PipeTransform {
  private readonly translateService = inject(TranslateService);

  transform(frenchText: string, params?: Record<string, string | number>): string;
  transform(frenchText: string | null | undefined, params?: Record<string, string | number>): string | null;
  transform(
    frenchText: string | null | undefined,
    params?: Record<string, string | number>,
  ): string | null {
    return frenchText == null ? null : this.translateService.translate(frenchText, params);
  }
}
