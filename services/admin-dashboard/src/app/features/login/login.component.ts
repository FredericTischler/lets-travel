import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { homeRouteFor } from '../../core/auth/roles';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { InputComponent } from '../../shared/ui/input/input.component';

/**
 * Login screen: POST /login with email + password, store the returned JWT
 * on success, then navigate to the home page of the user's role. Links to the
 * public sign-up page.
 */
@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink, AlertComponent, ButtonComponent, CardComponent, InputComponent],
  templateUrl: './login.component.html',
})
export class LoginComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  protected email = '';
  protected password = '';

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  submit(): void {
    this.submitting.set(true);
    this.error.set(null);

    this.authService.login({ email: this.email, password: this.password }).subscribe({
      next: () => {
        this.submitting.set(false);
        // Land on the home page of the role carried by the fresh token
        // (an unknown role would only bounce back to /login through the guards).
        this.router.navigateByUrl(homeRouteFor(this.authService.role()) ?? '/');
      },
      error: () => {
        this.submitting.set(false);
        this.error.set('Identifiants invalides.');
      },
    });
  }
}