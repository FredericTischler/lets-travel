import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../auth/auth.service';

/**
 * Route guard factory restricting a route to specific roles (see
 * docs/lets-travel-architecture-decisions.md §1): `ADMIN` implicitly passes
 * every check regardless of the roles listed, mirroring the role hierarchy
 * enforced backend-side.
 *
 * No token at all -> redirect to /login (same as {@link import('./auth.guard').authGuard}).
 * A token with a role not in `allowedRoles` (and not ADMIN) -> redirect to /users,
 * the one route every role can currently reach.
 */
export function roleGuard(...allowedRoles: readonly string[]): CanActivateFn {
  return () => {
    const authService = inject(AuthService);
    const router = inject(Router);

    if (!authService.isAuthenticated()) {
      return router.parseUrl('/login');
    }

    const role = authService.role();
    if (role === 'ADMIN' || (role !== null && allowedRoles.includes(role))) {
      return true;
    }

    return router.parseUrl('/users');
  };
}
