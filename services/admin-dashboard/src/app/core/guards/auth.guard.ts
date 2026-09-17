import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../auth/auth.service';

/**
 * Minimal auth guard: only checks that a token is stored, regardless of
 * role. Redirects to /login when no token is present. For a route that
 * must be restricted to specific roles, use {@link roleGuard} instead — see
 * `role.guard.ts`.
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.isAuthenticated()) {
    return true;
  }

  return router.parseUrl('/login');
};