import { DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { DestinationFormComponent } from './destination-form.component';
import { Destination, DestinationCreateInput, DestinationService } from './destination.service';
import {
  TRANSPORT_MODES,
  Transport,
  TransportMode,
  TransportService,
  TransportUpdateInput,
} from './transport.service';

/**
 * Admin destinations screen: every destination whatever its manager (GET
 * /destinations), plus create (POST), edit (PUT) and delete. Create/edit go
 * through the shared {@link DestinationFormComponent}; an admin types the
 * owning manager's id themselves (a Travel Manager's own screen — see
 * features/manager — pins it to their id). Every successful mutation reloads
 * the list from the server instead of mutating the local signal.
 *
 * Also hosts the outgoing-transports sub-view for a single destination at a
 * time (toggle per row, GET /destinations/{id}/transports) plus forms to
 * create (POST), edit (PATCH) and delete (DELETE) a one-hop transport from
 * that destination. The edit form reuses the same three fields as creation
 * (mode/duration, plus the optional departure/arrival times the create form
 * doesn't expose) and replaces the create form inline for the row being
 * edited, one at a time — same one-at-a-time pattern as destination edit.
 */
@Component({
  selector: 'app-destination-list',
  imports: [
    FormsModule,
    RouterLink,
    DecimalPipe,
    AlertComponent,
    ButtonComponent,
    CardComponent,
    InputComponent,
    DestinationFormComponent,
  ],
  templateUrl: './destination-list.component.html',
})
export class DestinationListComponent implements OnInit {
  private readonly destinationService = inject(DestinationService);
  private readonly transportService = inject(TransportService);

  private readonly form = viewChild(DestinationFormComponent);

  protected readonly destinations = signal<Destination[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // Create/edit state: null while creating, the destination while editing it.
  protected readonly editingDestination = signal<Destination | null>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

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

  // Edit-transport state: the transport being edited, or null. Scoped to the
  // currently expanded destination (collapsing it also clears this).
  protected readonly editingTransport = signal<Transport | null>(null);
  protected editTransportMode: TransportMode | '' = '';
  protected editTransportDuration: number | null = null;
  protected editTransportDepartureTime = '';
  protected editTransportArrivalTime = '';
  protected readonly savingTransport = signal(false);
  protected readonly editTransportError = signal<string | null>(null);

  // Delete-transport state.
  protected readonly deletingTransportId = signal<string | null>(null);
  protected readonly deleteTransportError = signal<string | null>(null);

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

  // --- Create / edit ---------------------------------------------------------

  startEdit(destination: Destination): void {
    this.saveError.set(null);
    this.editingDestination.set(destination);
  }

  cancelEdit(): void {
    this.saveError.set(null);
    this.editingDestination.set(null);
  }

  saveDestination(input: DestinationCreateInput): void {
    this.saving.set(true);
    this.saveError.set(null);

    const editing = this.editingDestination();
    // PUT has no managerId: ownership is assigned once, at creation.
    const { managerId: _managerId, ...updateInput } = input;
    const request = editing
      ? this.destinationService.update(editing.id, updateInput)
      : this.destinationService.create(input);

    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.editingDestination.set(null);
        this.form()?.reset();
        this.loadDestinations();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.saveError.set(
          extractErrorMessage(
            err,
            editing ? 'Impossible de modifier cette destination.' : 'Impossible de créer cette destination.',
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
        if (this.editingDestination()?.id === destination.id) {
          this.cancelEdit();
        }
        this.loadDestinations();
      },
      error: (err: unknown) => {
        this.deletingDestinationId.set(null);
        this.deleteError.set(extractErrorMessage(err, 'Impossible de supprimer cette destination.'));
      },
    });
  }

  // --- Transports ------------------------------------------------------------

  toggleTransports(destination: Destination): void {
    if (this.expandedDestinationId() === destination.id) {
      this.expandedDestinationId.set(null);
      return;
    }

    this.expandedDestinationId.set(destination.id);
    this.resetCreateTransportForm();
    this.cancelEditTransport();
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
      error: (err: unknown) => {
        this.transportsLoading.set(false);
        this.transportsError.set(
          extractErrorMessage(err, 'Impossible de charger les trajets de cette destination.'),
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
        error: (err: unknown) => {
          this.creatingTransport.set(false);
          this.createTransportError.set(extractErrorMessage(err, 'Impossible de créer ce trajet.'));
        },
      });
  }

  private resetCreateTransportForm(): void {
    this.createTransportToId = '';
    this.createTransportMode = '';
    this.createTransportDuration = null;
    this.createTransportError.set(null);
  }

  // --- Edit / delete a transport ----------------------------------------------

  startEditTransport(transport: Transport): void {
    this.editTransportError.set(null);
    this.editingTransport.set(transport);
    this.editTransportMode = transport.mode;
    this.editTransportDuration = transport.durationMinutes;
    // Not carried by the list/create response (see Transport), so the edit
    // form starts empty: leaving both blank keeps the existing schedule
    // untouched (undefined fields aren't sent, see saveEditTransport).
    this.editTransportDepartureTime = '';
    this.editTransportArrivalTime = '';
  }

  cancelEditTransport(): void {
    this.editTransportError.set(null);
    this.editingTransport.set(null);
  }

  saveEditTransport(fromId: string): void {
    const editing = this.editingTransport();
    if (!editing || !this.editTransportMode || this.editTransportDuration === null) {
      return;
    }

    const input: TransportUpdateInput = {
      mode: this.editTransportMode,
      durationMinutes: this.editTransportDuration,
      departureTime: this.editTransportDepartureTime || undefined,
      arrivalTime: this.editTransportArrivalTime || undefined,
    };

    this.savingTransport.set(true);
    this.editTransportError.set(null);

    this.transportService.update(fromId, editing.id, input).subscribe({
      next: () => {
        this.savingTransport.set(false);
        this.editingTransport.set(null);
        this.loadTransports(fromId);
      },
      error: (err: unknown) => {
        this.savingTransport.set(false);
        this.editTransportError.set(extractErrorMessage(err, 'Impossible de modifier ce trajet.'));
      },
    });
  }

  deleteTransport(fromId: string, transport: Transport): void {
    if (
      !confirm(`Supprimer le trajet vers ${transport.destinationName} (${transport.mode}) ?`)
    ) {
      return;
    }

    this.deletingTransportId.set(transport.id);
    this.deleteTransportError.set(null);

    this.transportService.delete(fromId, transport.id).subscribe({
      next: () => {
        this.deletingTransportId.set(null);
        if (this.editingTransport()?.id === transport.id) {
          this.cancelEditTransport();
        }
        this.loadTransports(fromId);
      },
      error: (err: unknown) => {
        this.deletingTransportId.set(null);
        this.deleteTransportError.set(extractErrorMessage(err, 'Impossible de supprimer ce trajet.'));
      },
    });
  }
}
