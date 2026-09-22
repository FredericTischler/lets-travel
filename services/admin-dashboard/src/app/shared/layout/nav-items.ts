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
 *
 * Labels go through `$localize` (i18n scaffolding, ADR §11 addendum): unlike
 * template text, a data-driven array has no `i18n` attribute to hang a
 * translation off, so this is the TypeScript-source form of the same
 * mechanism — `ng extract-i18n` picks up tagged templates here exactly like
 * `i18n="@@id"` in a template. Source (French) is returned unchanged outside
 * a localized `en` build (dev server, unit tests).
 */
export const NAV_ITEMS: readonly NavItem[] = [
  { label: $localize`:@@nav.travels:Voyages`, route: '/travels', roles: [ROLES.TRAVELER] },
  {
    label: $localize`:@@nav.mySubscriptions:Mes abonnements`,
    route: '/my-subscriptions',
    roles: [ROLES.TRAVELER],
  },
  { label: $localize`:@@nav.myStats:Mes statistiques`, route: '/my-stats', roles: [ROLES.TRAVELER] },
  {
    label: $localize`:@@nav.routes:Itinéraires`,
    route: '/destinations/routes',
    roles: [ROLES.TRAVELER],
  },
  {
    label: $localize`:@@nav.managerDashboard:Tableau de bord organisateur`,
    route: '/manager/dashboard',
    roles: [ROLES.TRAVEL_MANAGER],
  },
  {
    label: $localize`:@@nav.managerTravels:Mes voyages organisés`,
    route: '/manager/travels',
    roles: [ROLES.TRAVEL_MANAGER],
  },
  {
    label: $localize`:@@nav.adminDashboard:Tableau de bord admin`,
    route: '/admin/dashboard',
    roles: [ROLES.ADMIN],
  },
  { label: $localize`:@@nav.feedback:Avis`, route: '/admin/feedback', roles: [ROLES.ADMIN] },
  { label: $localize`:@@nav.reports:Signalements`, route: '/admin/reports', roles: [ROLES.ADMIN] },
  { label: $localize`:@@nav.users:Utilisateurs`, route: '/users', roles: [ROLES.ADMIN] },
  { label: $localize`:@@nav.payments:Paiements`, route: '/payments', roles: [ROLES.ADMIN] },
  { label: $localize`:@@nav.destinations:Destinations`, route: '/destinations', roles: [ROLES.ADMIN] },
];

/** The entries a user with the given role (as read from the token) may see. */
export function navItemsFor(role: string | null): NavItem[] {
  return NAV_ITEMS.filter((item) => hasAccess(role, item.roles));
}
