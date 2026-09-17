import { Component, forwardRef, input } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

export type InputType = 'text' | 'email' | 'password' | 'number';

/**
 * Labelled input field with an inline validation-error slot, implemented as
 * a `ControlValueAccessor` so it drops into existing template-driven forms
 * unchanged: consumers keep `[(ngModel)]`, `name`, `required`, etc. exactly
 * as they were on the native `<input>` before.
 *
 * `type="number"` mirrors Angular's built-in `NumberValueAccessor` (empty
 * string -> `null`, otherwise `parseFloat`) so numeric fields such as the
 * payment amount keep emitting `number | null` to the model, not a string.
 *
 * The `error` input is opinionated: nothing observed in the four screens
 * currently has a genuine per-field error (validation feedback is always a
 * form-level message), so no template passes it — it exists purely so this
 * component's public API answers the underlying requirement without any
 * screen having to invent a per-field error where none exists.
 */
@Component({
  selector: 'app-input',
  templateUrl: './input.component.html',
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => InputComponent),
      multi: true,
    },
  ],
})
export class InputComponent implements ControlValueAccessor {
  readonly fieldId = input.required<string>();
  readonly label = input.required<string>();
  readonly type = input<InputType>('text');
  readonly required = input(false);
  readonly autocomplete = input<string>('off');
  readonly error = input<string | null>(null);

  protected displayValue = '';
  protected disabled = false;

  private onChange: (value: string | number | null) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(value: string | number | null): void {
    this.displayValue = value === null || value === undefined ? '' : String(value);
  }

  registerOnChange(fn: (value: string | number | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  protected handleInput(rawValue: string): void {
    this.displayValue = rawValue;
    if (this.type() === 'number') {
      this.onChange(rawValue === '' ? null : parseFloat(rawValue));
    } else {
      this.onChange(rawValue);
    }
  }

  protected handleBlur(): void {
    this.onTouched();
  }
}