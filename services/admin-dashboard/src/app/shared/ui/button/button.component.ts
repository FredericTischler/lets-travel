import { Component, computed, input, output } from '@angular/core';

export type ButtonVariant = 'primary' | 'secondary' | 'danger';

/**
 * Reusable button: three visual variants (primary/secondary/danger — danger
 * is reserved for destructive actions such as delete), plus the native
 * `type`/`disabled` behaviour a form needs (a `type="submit"` button nested
 * inside this component still triggers the ancestor `<form>`'s `ngSubmit`,
 * since the projected/template markup is real light-DOM, not encapsulated).
 *
 * Purely presentational: no business logic. Consumers keep wiring
 * `(click)` handlers and `[disabled]` state exactly as before.
 */
@Component({
  selector: 'app-button',
  templateUrl: './button.component.html',
})
export class ButtonComponent {
  readonly variant = input<ButtonVariant>('primary');
  readonly type = input<'button' | 'submit'>('button');
  readonly disabled = input(false);

  readonly clicked = output<void>();

  protected readonly classes = computed(() => {
    const base =
      'inline-flex items-center justify-center rounded-md px-3 py-1.5 text-sm font-medium ' +
      'transition-colors disabled:cursor-not-allowed disabled:opacity-50';
    const variants: Record<ButtonVariant, string> = {
      primary:
        'bg-indigo-600 text-white hover:bg-indigo-500 dark:bg-indigo-500 dark:hover:bg-indigo-400',
      secondary:
        'border border-slate-300 text-slate-700 hover:bg-slate-100 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-800',
      danger:
        'bg-red-600 text-white hover:bg-red-500 dark:bg-red-500 dark:hover:bg-red-400',
    };
    return `${base} ${variants[this.variant()]}`;
  });
}