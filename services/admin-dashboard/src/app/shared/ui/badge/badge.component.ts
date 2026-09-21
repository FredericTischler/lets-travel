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
    const base = 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium';
    const tones: Record<BadgeTone, string> = {
      neutral: 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-200',
      info: 'bg-indigo-100 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300',
      success: 'bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-300',
      warning: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300',
      danger: 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-300',
    };
    return `${base} ${tones[this.tone()]}`;
  });
}
