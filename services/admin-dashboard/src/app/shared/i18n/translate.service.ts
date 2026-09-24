import { Injectable, signal } from '@angular/core';

import { EN_DICTIONARY } from './en.dictionary';

export type Locale = 'fr' | 'en';

const STORAGE_KEY = 'admin-dashboard.locale';
const DEFAULT_LOCALE: Locale = 'fr';

/**
 * Runtime FR/EN switch — deliberately not Angular's official i18n
 * (`@angular/localize`): that mechanism needs a separate build per locale
 * (extraction, XLIFF files, locale-prefixed output), which doesn't fit this
 * project's single static build served by one nginx container (see
 * services/admin-dashboard/Dockerfile) or a same-session language toggle.
 * A small signal-backed service + pipe gives both for the cost of one file.
 *
 * <p><b>Translation keys are the French source text itself</b> (see
 * en.dictionary.ts), not an arbitrary identifier: French is this app's
 * native language and default locale, so the "French" translation of a
 * French string is always the string itself — nothing to keep in sync, and
 * a key can never silently drift from its own reference text the way a
 * `nav.travels`-style identifier could.</p>
 */
@Injectable({ providedIn: 'root' })
export class TranslateService {
  readonly locale = signal<Locale>(readInitialLocale());

  setLocale(locale: Locale): void {
    this.locale.set(locale);
    try {
      localStorage.setItem(STORAGE_KEY, locale);
    } catch {
      // Storage blocked/full: the choice just doesn't survive a reload.
    }
  }

  /**
   * `frenchText` translated to the current locale, or itself unchanged for
   * `fr` (or for an `en` string with no dictionary entry — a missing
   * translation degrades to French rather than to a raw, meaningless key).
   * `params` substitutes `{{name}}` placeholders shared by both languages'
   * versions of the string.
   */
  translate(frenchText: string, params?: Record<string, string | number>): string {
    let text = this.locale() === 'en' ? (EN_DICTIONARY[frenchText] ?? frenchText) : frenchText;
    if (params) {
      for (const [key, value] of Object.entries(params)) {
        text = text.replaceAll(`{{${key}}}`, String(value));
      }
    }
    return text;
  }
}

function readInitialLocale(): Locale {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'en' ? 'en' : DEFAULT_LOCALE;
  } catch {
    return DEFAULT_LOCALE;
  }
}
