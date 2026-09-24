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
import { Route, TRANSPORT_MODES, Transport, TransportMode, TransportService } from './transport.service';

/**
 * Admin destinations screen: every destination whatever its manager (GET
 * /destinations), plus create (POST), edit (PUT) and delete. Create/edit go
 * through the shared {@link DestinationFormComponent}; an admin types the
 * owning manager's id themselves (a Travel Manager's own screen — see
 * features/manager — pins it to their id). Every successful mutation reloads
 * the list from the server instead of mutating the local signal.
 *
 * Also hosts the outgoing-transports sub-view for a single destination at a
 * time (toggle per row, GET /destinations/{id}/transports): create (POST),
 * inline edit (PUT) and delete (DELETE) of a single-hop transport, plus a
 * multi-hop route finder (GET /destinations/{fromId}/routes/{toId}).
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

  // Inline edit-transport form state (one transport at a time).
  protected readonly editingTransportId = signal<string | null>(null);
  protected editTransportMode: TransportMode | '' = '';
  protected editTransportDuration: number | null = null;
  protected readonly updatingTransport = signal(false);
  protected readonly updateTransportError = signal<string | null>(null);

  // Delete-transport state.
  protected readonly deletingTransportId = signal<string | null>(null);
  protected readonly deleteTransportError = signal<string | null>(null);

  // Multi-hop route finder state.
  protected routeToId = '';
  protected readonly routeResult = signal<Route | null>(null);
  protected readonly routeLoading = signal(false);
  protected readonly routeError = signal<string | null>(null);

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
    this.resetRouteFinder();
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

  startEditTransport(transport: Transport): void {
    this.updateTransportError.set(null);
    this.editingTransportId.set(transport.id);
    this.editTransportMode = transport.mode;
    this.editTransportDuration = transport.durationMinutes;
  }

  cancelEditTransport(): void {
    this.updateTransportError.set(null);
    this.editingTransportId.set(null);
  }

  saveTransportEdit(fromId: string): void {
    const transportId = this.editingTransportId();
    if (!transportId || !this.editTransportMode || this.editTransportDuration === null) {
      return;
    }

    this.updatingTransport.set(true);
    this.updateTransportError.set(null);

    this.transportService
      .update(fromId, transportId, this.editTransportMode, this.editTransportDuration)
      .subscribe({
        next: () => {
          this.updatingTransport.set(false);
          this.cancelEditTransport();
          this.loadTransports(fromId);
        },
        error: (err: unknown) => {
          this.updatingTransport.set(false);
          this.updateTransportError.set(extractErrorMessage(err, 'Impossible de modifier ce trajet.'));
        },
      });
  }

  deleteTransport(fromId: string, transport: Transport): void {
    if (!confirm(`Supprimer le trajet vers ${transport.destinationName} ?`)) {
      return;
    }

    this.deletingTransportId.set(transport.id);
    this.deleteTransportError.set(null);

    this.transportService.delete(fromId, transport.id).subscribe({
      next: () => {
        this.deletingTransportId.set(null);
        if (this.editingTransportId() === transport.id) {
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

  // --- Multi-hop route finder --------------------------------------------------

  findRoute(fromId: string): void {
    if (!this.routeToId) {
      return;
    }

    this.routeLoading.set(true);
    this.routeError.set(null);
    this.routeResult.set(null);

    this.transportService.findRoute(fromId, this.routeToId).subscribe({
      next: (route) => {
        this.routeLoading.set(false);
        this.routeResult.set(route);
      },
      error: (err: unknown) => {
        this.routeLoading.set(false);
        this.routeError.set(
          extractErrorMessage(err, "Aucun itinéraire trouvé vers cette destination."),
        );
      },
    });
  }

  private resetRouteFinder(): void {
    this.routeToId = '';
    this.routeResult.set(null);
    this.routeError.set(null);
  }
}
