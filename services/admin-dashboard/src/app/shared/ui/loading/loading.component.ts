import { Component, input } from '@angular/core';

/**
 * Replaces the bare `<p>Chargement…</p>` repeated across every screen with a
 * small spinner next to the same text — a perceived-performance cue ("this
 * is moving", not just static text) at zero cost to the ~20 call sites this
 * consolidates: `label` is a ready-to-display string, translated or not by
 * the caller exactly as before (this component does no translation itself,
 * so it never risks mixing FR/EN on a screen outside the i18n scope).
 *
 * The spinner is `aria-hidden` — the text alone is what a screen reader
 * announces — and respects `prefers-reduced-motion` (see `.app-spinner` in
 * styles.css).
 */
@Component({
  selector: 'app-loading',
  template: `
    <p class="flex items-center gap-2 text-ink-dim" [class]="size() === 'sm' ? 'text-xs' : 'text-sm'">
      <span class="app-spinner" aria-hidden="true"></span>
      {{ label() }}
    </p>
  `,
})
export class LoadingComponent {
  readonly label = input('Chargement…');
  readonly size = input<'sm' | 'base'>('base');
}
