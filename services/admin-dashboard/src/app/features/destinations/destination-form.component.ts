import {
  Component,
  ElementRef,
  effect,
  inject,
  input,
  output,
  untracked,
} from '@angular/core';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';

import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { Destination, DestinationCreateInput } from './destination.service';

const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** endDate must not be before startDate (backend rule, checked here to fail fast). */
function dateRange(group: AbstractControl): ValidationErrors | null {
  const start = group.get('startDate')?.value as string;
  const end = group.get('endDate')?.value as string;
  return start && end && end < start ? { dateRange: true } : null;
}

/**
 * Create/edit form of a destination (the subject's "Travel"), shared by the
 * Travel Manager's "my travels" screen and the admin's destinations screen.
 * It owns the form state and validation only: the parent performs the HTTP
 * call, and reports progress back through `saving`/`error`.
 *
 * Ownership: a Travel Manager may only create a travel in their own name, so
 * their screen passes `fixedManagerId` (the field is then hidden and always
 * sent). An admin screen leaves it `null` and gets a required manager-id
 * field. The manager is never editable on an existing destination (the PUT
 * body has no `managerId`), so the field is hidden in edit mode too.
 *
 * `activities`/`accommodations` are `FormArray`s so rows can be added and
 * removed dynamically.
 */
@Component({
  selector: 'app-destination-form',
  imports: [ReactiveFormsModule, AlertComponent, ButtonComponent, CardComponent, InputComponent],
  templateUrl: './destination-form.component.html',
})
export class DestinationFormComponent {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** The destination being edited, or `null` to create a new one. */
  readonly destination = input<Destination | null>(null);
  /** Manager id imposed by the screen (Travel Manager), or `null` (admin picks one). */
  readonly fixedManagerId = input<string | null>(null);
  readonly saving = input(false);
  readonly error = input<string | null>(null);

  readonly submitted = output<DestinationCreateInput>();
  readonly cancelled = output<void>();

  protected readonly form = this.fb.group(
    {
      name: this.fb.control('', [Validators.required]),
      country: this.fb.control('', [Validators.required]),
      startDate: this.fb.control('', [Validators.required]),
      endDate: this.fb.control('', [Validators.required]),
      price: this.fb.control<number | null>(null, [Validators.required, Validators.min(0)]),
      capacity: this.fb.control<number | null>(null, [
        Validators.required,
        Validators.min(1),
        Validators.pattern(/^\d+$/),
      ]),
      managerId: this.fb.control('', [Validators.required, Validators.pattern(UUID_PATTERN)]),
      activities: this.fb.array<ReturnType<typeof this.createActivityControl>>([]),
      accommodations: this.fb.array<ReturnType<typeof this.createAccommodationGroup>>([]),
    },
    { validators: [dateRange] },
  );

  constructor() {
    effect(() => {
      const destination = this.destination();
      const fixedManagerId = this.fixedManagerId();
      untracked(() => {
        this.load(destination);
        this.syncManagerControl(destination, fixedManagerId);
        // The form sits above the list: bring it into view when "Modifier" is
        // clicked on a row further down (optional call: absent in jsdom).
        if (destination !== null) {
          this.host.nativeElement.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
        }
      });
    });
  }

  protected get isEditing(): boolean {
    return this.destination() !== null;
  }

  protected get showManagerField(): boolean {
    return !this.isEditing && this.fixedManagerId() === null;
  }

  protected get activities() {
    return this.form.controls.activities;
  }

  protected get accommodations() {
    return this.form.controls.accommodations;
  }

  /** Empties the form (the parent calls this after a successful creation). */
  reset(): void {
    this.load(null);
    this.syncManagerControl(null, this.fixedManagerId());
  }

  addActivity(): void {
    this.activities.push(this.createActivityControl());
  }

  removeActivity(index: number): void {
    this.activities.removeAt(index);
  }

  addAccommodation(): void {
    this.accommodations.push(this.createAccommodationGroup());
  }

  removeAccommodation(index: number): void {
    this.accommodations.removeAt(index);
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    const destination = this.destination();
    this.submitted.emit({
      name: value.name.trim(),
      country: value.country.trim(),
      startDate: value.startDate,
      endDate: value.endDate,
      price: value.price as number,
      capacity: value.capacity as number,
      managerId: destination?.managerId ?? this.fixedManagerId() ?? value.managerId,
      activities: value.activities.map((activity) => activity.trim()),
      accommodations: value.accommodations.map((accommodation) => ({
        name: accommodation.name.trim(),
        type: accommodation.type.trim(),
        checkIn: accommodation.checkIn || null,
        checkOut: accommodation.checkOut || null,
      })),
    });
  }

  /** Message for a touched invalid field, or `null`. */
  protected fieldError(name: 'price' | 'capacity' | 'managerId'): string | null {
    const control = this.form.controls[name];
    if (!control.touched || control.valid) {
      return null;
    }
    switch (name) {
      case 'price':
        return 'Indiquez un prix supérieur ou égal à 0.';
      case 'capacity':
        return 'Indiquez une capacité entière d’au moins 1.';
      default:
        return 'Indiquez un identifiant (UUID) d’organisateur valide.';
    }
  }

  protected get dateRangeError(): boolean {
    return this.form.hasError('dateRange') && this.form.controls.endDate.touched;
  }

  private createActivityControl(name = '') {
    return this.fb.control(name, [Validators.required]);
  }

  private createAccommodationGroup(accommodation?: {
    name: string;
    type: string;
    checkIn: string | null;
    checkOut: string | null;
  }) {
    return this.fb.group({
      name: this.fb.control(accommodation?.name ?? '', [Validators.required]),
      type: this.fb.control(accommodation?.type ?? '', [Validators.required]),
      checkIn: this.fb.control(accommodation?.checkIn ?? ''),
      checkOut: this.fb.control(accommodation?.checkOut ?? ''),
    });
  }

  private load(destination: Destination | null): void {
    this.activities.clear();
    this.accommodations.clear();

    if (destination === null) {
      this.form.reset({
        name: '',
        country: '',
        startDate: '',
        endDate: '',
        price: null,
        capacity: null,
        managerId: '',
      });
      return;
    }

    destination.activities.forEach((activity) =>
      this.activities.push(this.createActivityControl(activity.name)),
    );
    destination.accommodations.forEach((accommodation) =>
      this.accommodations.push(this.createAccommodationGroup(accommodation)),
    );
    this.form.patchValue({
      name: destination.name,
      country: destination.country,
      startDate: destination.startDate,
      endDate: destination.endDate,
      price: destination.price,
      capacity: destination.capacity,
    });
  }

  /**
   * The manager-id control only takes part in validation when the admin has
   * to type one (creation, no fixed id); otherwise it is disabled so it never
   * blocks the submit.
   */
  private syncManagerControl(destination: Destination | null, fixedManagerId: string | null): void {
    const control = this.form.controls.managerId;
    if (destination === null && fixedManagerId === null) {
      control.enable();
    } else {
      control.disable();
    }
    if (fixedManagerId !== null) {
      control.setValue(fixedManagerId);
    }
  }
}
