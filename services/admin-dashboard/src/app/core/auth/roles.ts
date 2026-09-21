/**
 * The three roles carried by the `role` claim of the identity-service JWT
 * (docs/lets-travel-architecture-decisions.md §1).
 */
export const ROLES = {
  ADMIN: 'ADMIN',
  TRAVEL_MANAGER: 'TRAVEL_MANAGER',
  TRAVELER: 'TRAVELER',
} as const;

export type Role = (typeof ROLES)[keyof typeof ROLES];

/** French display labels, used by the role badge of the app shell. */
export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Administrateur',
  TRAVEL_MANAGER: 'Organisateur',
  TRAVELER: 'Voyageur',
};

/** Roles offered by the public sign-up form: ADMIN is never self-service. */
export const SIGN_UP_ROLES: readonly Role[] = [ROLES.TRAVELER, ROLES.TRAVEL_MANAGER];

export function isRole(value: unknown): value is Role {
  return typeof value === 'string' && Object.values<string>(ROLES).includes(value);
}

/**
 * Roles a given role can act as — the hierarchy of the subject, mirrored from
 * the backend (`ADMIN` can do everything a `TRAVEL_MANAGER` and a `TRAVELER`
 * can, a `TRAVEL_MANAGER` can do everything a `TRAVELER` can).
 */
export function impliedRoles(role: Role): readonly Role[] {
  switch (role) {
    case ROLES.ADMIN:
      return [ROLES.ADMIN, ROLES.TRAVEL_MANAGER, ROLES.TRAVELER];
    case ROLES.TRAVEL_MANAGER:
      return [ROLES.TRAVEL_MANAGER, ROLES.TRAVELER];
    default:
      return [ROLES.TRAVELER];
  }
}

/**
 * True when `role` (as read from the token, possibly `null` or unknown) may
 * reach something restricted to `allowed`, taking the role hierarchy into
 * account. An unknown or missing role never has access.
 */
export function hasAccess(role: string | null, allowed: readonly Role[]): boolean {
  if (!isRole(role)) {
    return false;
  }
  return impliedRoles(role).some((implied) => allowed.includes(implied));
}

/** Landing route of each role; `null` for a missing/unknown role. */
export function homeRouteFor(role: string | null): string | null {
  switch (role) {
    case ROLES.ADMIN:
      return '/users';
    case ROLES.TRAVEL_MANAGER:
      return '/manager/travels';
    case ROLES.TRAVELER:
      return '/travels';
    default:
      return null;
  }
}
