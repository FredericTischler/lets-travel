import { Component, computed, input } from '@angular/core';

export type BadgeTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

/**
 * Small pill for a status or a role (subscription status, report status,
 * the role of the logged-in user). Purely presentational: the caller picks
 * the tone and projects the label.
 */
@Component({
  selector: 'app-badge',
  template: `<span [class]="classes()"><ng-content /></span>`,
})
export class BadgeComponent {
  readonly tone = input<BadgeTone>('neutral');

  protected readonly classes = computed(() => {
    const base =
      'inline-flex items-center border px-2 py-0.5 font-mono text-[11px] font-medium tracking-wide uppercase';
    const tones: Record<BadgeTone, string> = {
      neutral: 'border-line-strong text-ink-dim',
      info: 'border-amber text-amber',
      success: 'border-teal text-teal',
      warning: 'border-amber text-amber',
      danger: 'border-red text-red',
    };
    return `${base} ${tones[this.tone()]}`;
  });
}
