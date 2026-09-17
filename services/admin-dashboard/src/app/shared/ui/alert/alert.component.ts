import { Component, computed, input } from '@angular/core';

export type AlertVariant = 'error' | 'success';

/**
 * Styled message box for error/success feedback — replaces the bare
 * `<p role="alert">{{ message }}</p>` previously used for backend/frontend
 * error text. Keeps `role="alert"` for the same accessibility behaviour.
 *
 * Only the `error` variant is exercised today: none of the four screens
 * currently produce a success message (mutations just silently reload the
 * list), so `success` exists to satisfy the required variant set without
 * inventing new success-messaging behaviour that isn't there today.
 */
@Component({
  selector: 'app-alert',
  templateUrl: './alert.component.html',
})
export class AlertComponent {
  readonly variant = input<AlertVariant>('error');

  protected readonly classes = computed(() => {
    const base = 'rounded-md border px-3 py-2 text-sm';
    const variants: Record<AlertVariant, string> = {
      error:
        'border-red-300 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300',
      success:
        'border-green-300 bg-green-50 text-green-700 dark:border-green-800 dark:bg-green-950 dark:text-green-300',
    };
    return `${base} ${variants[this.variant()]}`;
  });
}