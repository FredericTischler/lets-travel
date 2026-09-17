import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { User, UserService } from './user.service';

/**
 * Users screen: list (GET /users) plus create, edit (email only) and
 * delete actions. Every successful mutation reloads the list from the
 * server instead of mutating the local signal directly.
 */
@Component({
  selector: 'app-user-list',
  imports: [FormsModule, AlertComponent, ButtonComponent, CardComponent, InputComponent],
  templateUrl: './user-list.component.html',
})
export class UserListComponent implements OnInit {
  private readonly userService = inject(UserService);

  protected readonly users = signal<User[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // Create form state.
  protected createEmail = '';
  protected createPassword = '';
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  // Edit form state (at most one row editable at a time).
  protected readonly editingUserId = signal<string | null>(null);
  protected editEmail = '';
  protected readonly editing = signal(false);
  protected readonly editError = signal<string | null>(null);

  // Delete state.
  protected readonly deletingUserId = signal<string | null>(null);
  protected readonly deleteError = signal<string | null>(null);

  ngOnInit(): void {
    this.loadUsers();
  }

  private loadUsers(): void {
    this.loading.set(true);
    this.error.set(null);
    this.userService.list().subscribe({
      next: (users) => {
        this.users.set(users);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossible de charger la liste des utilisateurs.');
        this.loading.set(false);
      },
    });
  }

  createUser(): void {
    this.creating.set(true);
    this.createError.set(null);

    this.userService.create(this.createEmail, this.createPassword).subscribe({
      next: () => {
        this.creating.set(false);
        this.createEmail = '';
        this.createPassword = '';
        this.loadUsers();
      },
      error: (err: HttpErrorResponse) => {
        this.creating.set(false);
        this.createError.set(this.extractErrorMessage(err, 'Impossible de créer cet utilisateur.'));
      },
    });
  }

  startEdit(user: User): void {
    this.editingUserId.set(user.id);
    this.editEmail = user.email;
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingUserId.set(null);
    this.editEmail = '';
    this.editError.set(null);
  }

  saveEdit(user: User): void {
    this.editing.set(true);
    this.editError.set(null);

    this.userService.update(user.id, this.editEmail).subscribe({
      next: () => {
        this.editing.set(false);
        this.editingUserId.set(null);
        this.editEmail = '';
        this.loadUsers();
      },
      error: (err: HttpErrorResponse) => {
        this.editing.set(false);
        this.editError.set(this.extractErrorMessage(err, 'Impossible de modifier cet utilisateur.'));
      },
    });
  }

  deleteUser(user: User): void {
    if (!confirm(`Supprimer l'utilisateur ${user.email} ?`)) {
      return;
    }

    this.deletingUserId.set(user.id);
    this.deleteError.set(null);

    this.userService.delete(user.id).subscribe({
      next: () => {
        this.deletingUserId.set(null);
        this.loadUsers();
      },
      error: (err: HttpErrorResponse) => {
        this.deletingUserId.set(null);
        this.deleteError.set(this.extractErrorMessage(err, 'Impossible de supprimer cet utilisateur.'));
      },
    });
  }

  private extractErrorMessage(err: HttpErrorResponse, fallback: string): string {
    return typeof err.error?.error === 'string' ? err.error.error : fallback;
  }
}