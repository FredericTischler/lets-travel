import { Component, computed, input } from '@angular/core';

export type AlertVariant = 'error' | 'success' | 'warning';

/**
 * Styled message box for error/success/warning feedback — replaces the bare
 * `<p role="alert">{{ message }}</p>` previously used for backend/frontend
 * error text. Keeps `role="alert"` for the same accessibility behaviour
 * (callers needing `role="status"` instead, e.g. a non-error degraded-service
 * notice, set that attribute themselves — it still binds to this component's
 * classes).
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