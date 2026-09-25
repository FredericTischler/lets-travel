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
      'inline-flex items-center justify-center gap-1.5 border px-3 py-1.5 font-mono text-xs font-medium ' +
      'tracking-wide uppercase transition-[filter,background-color,color] active:brightness-95 ' +
      'disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus-visible:outline ' +
      'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber';
    const variants: Record<ButtonVariant, string> = {
      primary: 'border-amber bg-amber text-amber-ink hover:brightness-110',
      secondary: 'border-line-strong text-ink hover:bg-surface-2',
      danger: 'border-red text-red hover:bg-red hover:text-surface',
    };
    return `${base} ${variants[this.variant()]}`;
  });
}