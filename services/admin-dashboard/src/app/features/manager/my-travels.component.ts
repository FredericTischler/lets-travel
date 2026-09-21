import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { DestinationFormComponent } from '../destinations/destination-form.component';
import { Destination, DestinationCreateInput, DestinationService } from '../destinations/destination.service';

/**
 * Travel Manager's "my travels": the destinations whose `managerId` is the
 * caller's own id, with create / edit / delete and a link to each travel's
 * subscriber list.
 *
 * The backend has no "by manager" query, so the list is GET /destinations
 * filtered client-side on `managerId`. The filter is a convenience, not a
 * security boundary: the backend independently refuses (403) any write on
 * someone else's travel, and a create whose `managerId` is not the caller's.
 * The form is given the caller's id as `fixedManagerId`, so the field is not
 * even shown.
 */
@Component({
  selector: 'app-my-travels',
  imports: [
    RouterLink,
    DecimalPipe,
    AlertComponent,
    ButtonComponent,
    CardComponent,
    DestinationFormComponent,
  ],
  templateUrl: './my-travels.component.html',
})
export class MyTravelsComponent implements OnInit {
  private readonly destinationService = inject(DestinationService);
  private readonly authService = inject(AuthService);

  private readonly form = viewChild(DestinationFormComponent);

  /** The caller's own id (JWT `sub`), used both to filter and to own new travels. */
  protected readonly managerId = this.authService.getCurrentUserId();

  protected readonly travels = signal<Destination[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly editing = signal<Destination | null>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly deletingId = signal<string | null>(null);
  protected readonly deleteError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.destinationService.list().subscribe({
      next: (destinations) => {
        this.travels.set(destinations.filter((d) => d.managerId === this.managerId));
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger vos voyages.'));
        this.loading.set(false);
      },
    });
  }

  startEdit(travel: Destination): void {
    this.saveError.set(null);
    this.editing.set(travel);
  }

  cancelEdit(): void {
    this.saveError.set(null);
    this.editing.set(null);
  }

  save(input: DestinationCreateInput): void {
    this.saving.set(true);
    this.saveError.set(null);

    const editing = this.editing();
    // PUT has no managerId: ownership is assigned once, at creation.
    const { managerId: _managerId, ...updateInput } = input;
    const request = editing
      ? this.destinationService.update(editing.id, updateInput)
      : this.destinationService.create(input);

    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.editing.set(null);
        this.form()?.reset();
        this.load();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.saveError.set(
          extractErrorMessage(
            err,
            editing ? 'Impossible de modifier ce voyage.' : 'Impossible de créer ce voyage.',
          ),
        );
      },
    });
  }

  remove(travel: Destination): void {
    if (!confirm(`Supprimer le voyage ${travel.name} ?`)) {
      return;
    }

    this.deletingId.set(travel.id);
    this.deleteError.set(null);
    this.destinationService.delete(travel.id).subscribe({
      next: () => {
        this.deletingId.set(null);
        if (this.editing()?.id === travel.id) {
          this.cancelEdit();
        }
        this.load();
      },
      error: (err: unknown) => {
        this.deletingId.set(null);
        this.deleteError.set(extractErrorMessage(err, 'Impossible de supprimer ce voyage.'));
      },
    });
  }
}
