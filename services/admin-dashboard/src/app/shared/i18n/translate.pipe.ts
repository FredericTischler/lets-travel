import { Pipe, PipeTransform, inject } from '@angular/core';

import { TranslateService } from './translate.service';

/**
 * `{{ 'French text' | translate }}` / `{{ 'Voici {{n}}' | translate: { n: 3 } }}`.
 *
 * `pure: false` is required, not optional: a pure pipe is memoized by its
 * *argument's* identity, and that argument (the French string literal) never
 * changes — so Angular would never call `transform()` again after the first
 * render, no matter how many times `TranslateService.locale` changes inside
 * it. Reading a signal inside a pure pipe does not make the pipe reactive to
 * that signal; only re-invoking `transform()` does (confirmed live: toggling
 * the language updated `localStorage` correctly but never re-rendered a
 * single translated string until this was set). The cost is that
 * `transform()` now re-runs on every change-detection pass for every
 * `| translate` usage — a plain object lookup, negligible at this app's size.
 */
@Pipe({ name: 'translate', pure: false })
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
