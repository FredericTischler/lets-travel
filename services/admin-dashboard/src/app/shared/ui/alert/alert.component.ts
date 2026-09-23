import { Component, computed, input } from '@angular/core';

export type AlertVariant = 'error' | 'success' | 'warning';

/**
 * Styled message box for error/success/warning feedback — replaces the bare
 * `<p role="alert">{{ message }}</p>` previously used for backend/frontend
 * error text. `error` keeps the assertive `role="alert"` (it demands
 * attention); `success`/`warning` use the polite `role="status"` — a
 * "service degraded, showing partial data" notice shouldn't interrupt a
 * screen reader mid-sentence the way a form error should.
 *
 * `warning` covers the "service degraded, showing partial data" notices
 * repeated identically across several dashboards before this variant existed.
 */
@Component({
  selector: 'app-alert',
  templateUrl: './alert.component.html',
})
export class AlertComponent {
  readonly variant = input<AlertVariant>('error');

  protected readonly role = computed<'alert' | 'status'>(() =>
    this.variant() === 'error' ? 'alert' : 'status',
  );

  protected readonly classes = computed(() => {
    const base = 'border-l-4 bg-surface-2 px-3 py-2 text-sm text-ink';
    const variants: Record<AlertVariant, string> = {
      error: 'border-l-red',
      success: 'border-l-teal',
      warning: 'border-l-amber',
    };
    return `${base} ${variants[this.variant()]}`;
  });
}