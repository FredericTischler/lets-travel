import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { Destination, DestinationInput, DestinationService } from './destination.service';
import { TRANSPORT_MODES, Transport, TransportMode, TransportService } from './transport.service';

/**
 * Destinations screen: list (GET /destinations) plus create (POST
 * /destinations), edit (PUT /destinations/{id}) and delete actions. Every
 * successful mutation reloads the list from the server instead of mutating
 * the local signal directly.
 *
 * The create/edit form is a single Reactive Forms `FormGroup`, shared by
 * both flows: creating starts from an empty form, clicking "Modifier" on a
 * row patches the same form with that destination's data and switches
 * `editingDestinationId` to its id, submit then dispatches to create() or
 * update() accordingly. `activities`/`accommodations` are `FormArray`s so
 * rows can be added/removed dynamically.
 *
 * Also hosts the outgoing-transports sub-view for a single destination at a
 * time (toggle per row, GET /destinations/{id}/transports) plus a form to
 * create a new one-hop transport from that destination (POST
 * /destinations/{fromId}/transports). No PATCH/DELETE on Transport exists
 * server-side, so none is simulated here. Transports are a separate
 * resource, not embedded in this screen's create/edit form.
 */
@Component({
  selector: 'app-destination-list',
  imports: [
    FormsModule,
    ReactiveFormsModule,
    AlertComponent,
    ButtonComponent,
    CardComponent,
    InputComponent,
  ],
  templateUrl: './destination-list.component.html',
})
export class DestinationListComponent implements OnInit {
  private readonly destinationService = inject(DestinationService);
  private readonly transportService = inject(TransportService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly destinations = signal<Destination[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // Create/edit form state. `editingDestinationId` is null while creating,
  // set to the destination's id while editing it.
  protected readonly editingDestinationId = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly destinationForm = this.fb.group({
    name: this.fb.control('', [Validators.required]),
    country: this.fb.control('', [Validators.required]),
    startDate: this.fb.control('', [Validators.required]),
    endDate: this.fb.control('', [Validators.required]),
    activities: this.fb.array<ReturnType<typeof this.createActivityControl>>([]),
    accommodations: this.fb.array<ReturnType<typeof this.createAccommodationGroup>>([]),
  });

  // Delete state.
  protected readonly deletingDestinationId = signal<string | null>(null);
  protected readonly deleteError = signal<string | null>(null);

  // Outgoing-transports sub-view state (one destination expanded at a time).
  protected readonly transportModes = TRANSPORT_MODES;
  protected readonly expandedDestinationId = signal<string | null>(null);
  protected readonly transports = signal<Transport[]>([]);
  protected readonly transportsLoading = signal(false);
  protected readonly transportsError = signal<string | null>(null);

  // Destinations selectable as the target of a new transport: every
  // destination except the one currently expanded, so the UI itself never
  // offers a self-loop (the backend also rejects it with 400, defence in depth).
  protected readonly transportTargets = computed(() =>
    this.destinations().filter((destination) => destination.id !== this.expandedDestinationId()),
  );

  // Create-transport form state.
  protected createTransportToId = '';
  protected createTransportMode: TransportMode | '' = '';
  protected createTransportDuration: number | null = null;
  protected readonly creatingTransport = signal(false);
  protected readonly createTransportError = signal<string | null>(null);

  ngOnInit(): void {
    this.loadDestinations();
  }

  private loadDestinations(): void {
    this.loading.set(true);
    this.error.set(null);
    this.destinationService.list().subscribe({
      next: (destinations) => {
        this.destinations.set(destinations);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossible de charger la liste des destinations.');
        this.loading.set(false);
      },
    });
  }

  // --- Activities FormArray -------------------------------------------------

  private createActivityControl(name = '') {
    return this.fb.control(name, [Validators.required]);
  }

  protected get activities() {
    return this.destinationForm.controls.activities;
  }

  addActivity(): void {
    this.activities.push(this.createActivityControl());
  }

  removeActivity(index: number): void {
    this.activities.removeAt(index);
  }

  // --- Accommodations FormArray ----------------------------------------------

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

  protected get accommodations() {
    return this.destinationForm.controls.accommodations;
  }

  addAccommodation(): void {
    this.accommodations.push(this.createAccommodationGroup());
  }

  removeAccommodation(index: number): void {
    this.accommodations.removeAt(index);
  }

  // --- Create / edit ---------------------------------------------------------

  startEdit(destination: Destination): void {
    this.editingDestinationId.set(destination.id);
    this.saveError.set(null);

    this.activities.clear();
    destination.activities.forEach((activity) =>
      this.activities.push(this.createActivityControl(activity.name)),
    );

    this.accommodations.clear();
    destination.accommodations.forEach((accommodation) =>
      this.accommodations.push(
        this.createAccommodationGroup({
          name: accommodation.name,
          type: accommodation.type,
          checkIn: accommodation.checkIn,
          checkOut: accommodation.checkOut,
        }),
      ),
    );

    this.destinationForm.patchValue({
      name: destination.name,
      country: destination.country,
      startDate: destination.startDate,
      endDate: destination.endDate,
    });
  }

  cancelEdit(): void {
    this.resetForm();
  }

  private resetForm(): void {
    this.editingDestinationId.set(null);
    this.saveError.set(null);
    this.activities.clear();
    this.accommodations.clear();
    this.destinationForm.reset({ name: '', country: '', startDate: '', endDate: '' });
  }

  saveDestination(): void {
    if (this.destinationForm.invalid) {
      this.destinationForm.markAllAsTouched();
      return;
    }

    const value = this.destinationForm.getRawValue();
    const input: DestinationInput = {
      name: value.name,
      country: value.country,
      startDate: value.startDate,
      endDate: value.endDate,
      activities: value.activities,
      accommodations: value.accommodations.map((accommodation) => ({
        name: accommodation.name,
        type: accommodation.type,
        checkIn: accommodation.checkIn || null,
        checkOut: accommodation.checkOut || null,
      })),
    };

    this.saving.set(true);
    this.saveError.set(null);

    const editingId = this.editingDestinationId();
    const request = editingId
      ? this.destinationService.update(editingId, input)
      : this.destinationService.create(input);

    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.resetForm();
        this.loadDestinations();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.saveError.set(
          this.extractErrorMessage(
            err,
            editingId ? 'Impossible de modifier cette destination.' : 'Impossible de créer cette destination.',
          ),
        );
      },
    });
  }

  deleteDestination(destination: Destination): void {
    if (!confirm(`Supprimer la destination ${destination.name} ?`)) {
      return;
    }

    this.deletingDestinationId.set(destination.id);
    this.deleteError.set(null);

    this.destinationService.delete(destination.id).subscribe({
      next: () => {
        this.deletingDestinationId.set(null);
        if (this.editingDestinationId() === destination.id) {
          this.resetForm();
        }
        this.loadDestinations();
      },
      error: (err: HttpErrorResponse) => {
        this.deletingDestinationId.set(null);
        this.deleteError.set(
          this.extractErrorMessage(err, 'Impossible de supprimer cette destination.'),
        );
      },
    });
  }

  toggleTransports(destination: Destination): void {
    if (this.expandedDestinationId() === destination.id) {
      this.expandedDestinationId.set(null);
      return;
    }

    this.expandedDestinationId.set(destination.id);
    this.resetCreateTransportForm();
    this.loadTransports(destination.id);
  }

  private loadTransports(fromId: string): void {
    this.transportsLoading.set(true);
    this.transportsError.set(null);

    this.transportService.listOutgoing(fromId).subscribe({
      next: (transports) => {
        this.transports.set(transports);
        this.transportsLoading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.transportsLoading.set(false);
        this.transportsError.set(
          this.extractErrorMessage(err, 'Impossible de charger les trajets de cette destination.'),
        );
      },
    });
  }

  createTransport(fromId: string): void {
    if (
      !this.createTransportToId ||
      !this.createTransportMode ||
      this.createTransportDuration === null
    ) {
      return;
    }

    this.creatingTransport.set(true);
    this.createTransportError.set(null);

    this.transportService
      .create(
        fromId,
        this.createTransportToId,
        this.createTransportMode,
        this.createTransportDuration,
      )
      .subscribe({
        next: () => {
          this.creatingTransport.set(false);
          this.resetCreateTransportForm();
          this.loadTransports(fromId);
        },
        error: (err: HttpErrorResponse) => {
          this.creatingTransport.set(false);
          this.createTransportError.set(
            this.extractErrorMessage(err, 'Impossible de créer ce trajet.'),
          );
        },
      });
  }

  private resetCreateTransportForm(): void {
    this.createTransportToId = '';
    this.createTransportMode = '';
    this.createTransportDuration = null;
    this.createTransportError.set(null);
  }

  private extractErrorMessage(err: HttpErrorResponse, fallback: string): string {
    return typeof err.error?.error === 'string' ? err.error.error : fallback;
  }
}
