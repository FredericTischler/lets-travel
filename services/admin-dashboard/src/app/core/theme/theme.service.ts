import { Injectable, effect, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'admin-dashboard.theme';
const DARK_CLASS = 'dark';

/**
 * Light/dark theme state, class-based (Tailwind `dark:` variant driven by
 * a `dark` class on <html>) and persisted in localStorage.
 *
 * The initial value is read directly from <html>'s class list rather than
 * from localStorage again, because an inline script in index.html already
 * applies the persisted theme to <html> before Angular bootstraps (to
 * avoid a flash of the wrong theme). This keeps a single source of truth
 * for "what the user chose last".
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<Theme>(
    document.documentElement.classList.contains(DARK_CLASS) ? 'dark' : 'light',
  );

  constructor() {
    effect(() => {
      const theme = this.theme();
      document.documentElement.classList.toggle(DARK_CLASS, theme === 'dark');
      localStorage.setItem(STORAGE_KEY, theme);
    });
  }

  toggle(): void {
    this.theme.update((current) => (current === 'dark' ? 'light' : 'dark'));
  }
}