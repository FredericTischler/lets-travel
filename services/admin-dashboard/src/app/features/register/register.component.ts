import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { ROLES, ROLE_LABELS, Role, SIGN_UP_ROLES, homeRouteFor } from '../../core/auth/roles';
import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { InputComponent } from '../../shared/ui/input/input.component';

/** Minimum password length enforced by identity-service (`@Size(min = 8)`). */
const MIN_PASSWORD_LENGTH = 8;

/**
 * Public sign-up: email, password and a choice between TRAVELER and
 * TRAVEL_MANAGER — ADMIN is deliberately absent from the choices
 * ({@link SIGN_UP_ROLES}). This is a UX choice, not the security control: the
 * backend itself refuses `role: ADMIN` on the public POST /users unless the
 * caller carries an ADMIN token (403), see identity-service UserService#create.
 *
 * On success the user is logged in with the same credentials and lands on the
 * home page of their role.
 */
@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink, AlertComponent, InputComponent],
  templateUrl: './register.component.html',
})
export class RegisterComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly currentYear = new Date().getFullYear();

  protected readonly roles: readonly { value: Role; label: string; hint: string }[] =
    SIGN_UP_ROLES.map((value) => ({
      value,
      label: ROLE_LABELS[value],
      hint:
        value === ROLES.TRAVELER
          ? 'Parcourir les voyages, s’inscrire et suivre vos participations.'
          : 'Créer et gérer vos propres voyages, en plus des fonctions voyageur.',
    }));

  protected email = '';
  protected password = '';
  protected role: Role = ROLES.TRAVELER;

  protected readonly minPasswordLength = MIN_PASSWORD_LENGTH;
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  submit(): void {
    if (this.password.length < MIN_PASSWORD_LENGTH) {
      this.error.set(`Le mot de passe doit contenir au moins ${MIN_PASSWORD_LENGTH} caractères.`);
      return;
    }
    // Defence in depth: never send a role the form does not offer.
    if (!SIGN_UP_ROLES.includes(this.role)) {
      this.error.set('Rôle invalide.');
      return;
    }

    this.submitting.set(true);
    this.error.set(null);

    const credentials = { email: this.email, password: this.password };
    this.authService
      .register({ ...credentials, role: this.role })
      .pipe(switchMap(() => this.authService.login(credentials)))
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.router.navigateByUrl(homeRouteFor(this.authService.role()) ?? '/login');
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          this.error.set(extractErrorMessage(err, 'Impossible de créer le compte.'));
        },
      });
  }
}
