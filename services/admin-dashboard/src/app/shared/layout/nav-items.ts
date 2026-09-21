import { ROLES, Role, hasAccess } from '../../core/auth/roles';

export interface NavItem {
  label: string;
  route: string;
  /**
   * Roles the entry is meant for. The role hierarchy applies (see
   * {@link hasAccess}): an ADMIN sees every entry, a TRAVEL_MANAGER also sees
   * the TRAVELER ones.
   */
  roles: readonly Role[];
}

/**
 * Every navigation entry of the authenticated area. The shell filters this
 * list by the current role; a new screen only needs an entry here (plus its
 * route, guarded with the same roles — see app.routes.ts).
 */
export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Voyages', route: '/travels', roles: [ROLES.TRAVELER] },
  { label: 'Mes abonnements', route: '/my-subscriptions', roles: [ROLES.TRAVELER] },
  { label: 'Mes voyages organisés', route: '/manager/travels', roles: [ROLES.TRAVEL_MANAGER] },
  { label: 'Signalements', route: '/admin/reports', roles: [ROLES.ADMIN] },
  { label: 'Utilisateurs', route: '/users', roles: [ROLES.ADMIN] },
  { label: 'Paiements', route: '/payments', roles: [ROLES.ADMIN] },
  { label: 'Destinations', route: '/destinations', roles: [ROLES.ADMIN] },
];

/** The entries a user with the given role (as read from the token) may see. */
export function navItemsFor(role: string | null): NavItem[] {
  return NAV_ITEMS.filter((item) => hasAccess(role, item.roles));
}
