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
  /**
   * Optional group label. An ADMIN inherits every Traveler/Manager entry on
   * top of their own ({@link hasAccess}'s hierarchy), which otherwise mixes
   * eleven links in one flat list — grouped entries render under a single
   * collapsible section instead ("Organisateur", "Administration"), so each
   * audience stays visually separated when a role inherits more than its own.
   */
  group?: string;
}

/**
 * Every navigation entry of the authenticated area. The shell filters this
 * list by the current role; a new screen only needs an entry here (plus its
 * route, guarded with the same roles — see app.routes.ts).
 */
export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Voyages', route: '/travels', roles: [ROLES.TRAVELER] },
  { label: 'Mes abonnements', route: '/my-subscriptions', roles: [ROLES.TRAVELER] },
  { label: 'Mes statistiques', route: '/my-stats', roles: [ROLES.TRAVELER] },
  {
    label: 'Tableau de bord organisateur',
    route: '/manager/dashboard',
    roles: [ROLES.TRAVEL_MANAGER],
    group: 'Organisateur',
  },
  {
    label: 'Mes voyages organisés',
    route: '/manager/travels',
    roles: [ROLES.TRAVEL_MANAGER],
    group: 'Organisateur',
  },
  { label: 'Tableau de bord admin', route: '/admin/dashboard', roles: [ROLES.ADMIN], group: 'Administration' },
  { label: 'Avis', route: '/admin/feedback', roles: [ROLES.ADMIN], group: 'Administration' },
  { label: 'Signalements', route: '/admin/reports', roles: [ROLES.ADMIN], group: 'Administration' },
  { label: 'Utilisateurs', route: '/users', roles: [ROLES.ADMIN], group: 'Administration' },
  { label: 'Paiements', route: '/payments', roles: [ROLES.ADMIN], group: 'Administration' },
  { label: 'Destinations', route: '/destinations', roles: [ROLES.ADMIN], group: 'Administration' },
];

/** The entries a user with the given role (as read from the token) may see. */
export function navItemsFor(role: string | null): NavItem[] {
  return NAV_ITEMS.filter((item) => hasAccess(role, item.roles));
}

/** One block of the sidebar: `label: null` is the flat top-level list, a named label is a collapsible group. */
export interface NavSection {
  label: string | null;
  items: NavItem[];
}

/**
 * {@link navItemsFor}, partitioned into the flat list and its named groups
 * (in first-seen order), each rendered as its own collapsible section.
 */
export function navSectionsFor(role: string | null): NavSection[] {
  const items = navItemsFor(role);
  const sections: NavSection[] = [{ label: null, items: [] }];
  const byLabel = new Map<string, NavSection>();

  for (const item of items) {
    if (!item.group) {
      sections[0].items.push(item);
      continue;
    }
    let section = byLabel.get(item.group);
    if (!section) {
      section = { label: item.group, items: [] };
      byLabel.set(item.group, section);
      sections.push(section);
    }
    section.items.push(item);
  }

  return sections;
}
