import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { Role, hasAccess, homeRouteFor } from '../auth/roles';

/**
 * Where to send an authenticated user who may not see the requested route:
 * back to the landing page of their own role, or — if the token carries no
 * (or an unrecognised) role, e.g. a pre-Phase-1 token — out of the app, with
 * the unusable token discarded so the login screen does not bounce back.
 */
function fallbackFor(authService: AuthService, router: Router) {
  const home = homeRouteFor(authService.role());
  if (home === null) {
    authService.logout();
    return router.parseUrl('/login');
  }
  return router.parseUrl(home);
}

/**
 * Route guard factory restricting a route to specific roles (see
 * docs/lets-travel-architecture-decisions.md §1 and §8). The role hierarchy
 * applies ({@link hasAccess}): `ADMIN` passes every check and a
 * `TRAVEL_MANAGER` passes every `TRAVELER` check.
 *
 * No token at all -> /login. A token whose role may not see the route ->
 * the landing page of that role, or /login for an unknown role.
 */
export function roleGuard(...allowedRoles: readonly Role[]): CanActivateFn {
  return () => {
    const authService = inject(AuthService);
    const router = inject(Router);

    if (!authService.isAuthenticated()) {
      return router.parseUrl('/login');
    }

    if (hasAccess(authService.role(), allowedRoles)) {
      return true;
    }

    return fallbackFor(authService, router);
  };
}

/**
 * Guard of the empty path: sends the visitor to the landing page of their
 * role (or /login when unauthenticated / role unknown). Never renders.
 */
export const homeGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (!authService.isAuthenticated()) {
    return router.parseUrl('/login');
  }
  return fallbackFor(authService, router);
};
