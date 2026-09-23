import { NAV_ITEMS, navItemsFor, navSectionsFor } from './nav-items';

describe('navItemsFor()', () => {
  const labels = (role: string | null) => navItemsFor(role).map((item) => item.label);

  it('gives a TRAVELER only the traveler entries', () => {
    expect(labels('TRAVELER')).toEqual(['Voyages', 'Mes abonnements', 'Mes statistiques']);
  });

  it('gives a TRAVEL_MANAGER the manager entries on top of the traveler ones', () => {
    expect(labels('TRAVEL_MANAGER')).toEqual([
      'Voyages',
      'Mes abonnements',
      'Mes statistiques',
      'Tableau de bord organisateur',
      'Mes voyages organisés',
    ]);
  });

  it('gives an ADMIN every entry (hierarchy)', () => {
    expect(navItemsFor('ADMIN')).toEqual([...NAV_ITEMS]);
  });

  it('shows nothing for a missing or unknown role', () => {
    expect(navItemsFor(null)).toEqual([]);
    expect(navItemsFor('SUPERUSER')).toEqual([]);
  });

  it('gives an ADMIN the two admin dashboards entries', () => {
    expect(labels('ADMIN')).toEqual(expect.arrayContaining(['Tableau de bord admin', 'Avis']));
    expect(labels('TRAVEL_MANAGER')).not.toContain('Tableau de bord admin');
    expect(labels('TRAVELER')).not.toContain('Tableau de bord organisateur');
  });

  it('only points to routes starting with a slash', () => {
    for (const item of NAV_ITEMS) {
      expect(item.route.startsWith('/')).toBe(true);
    }
  });
});

describe('navSectionsFor()', () => {
  const sectionLabels = (role: string | null) => navSectionsFor(role).map((s) => s.label);
  const itemLabels = (role: string | null, label: string | null) =>
    navSectionsFor(role)
      .find((s) => s.label === label)
      ?.items.map((item) => item.label);

  it('puts a TRAVELER\'s entries in the flat (unlabelled) section only', () => {
    expect(sectionLabels('TRAVELER')).toEqual([null]);
    expect(itemLabels('TRAVELER', null)).toEqual(['Voyages', 'Mes abonnements', 'Mes statistiques']);
  });

  it('groups a TRAVEL_MANAGER\'s own entries under "Organisateur", leaving the traveler ones flat', () => {
    expect(sectionLabels('TRAVEL_MANAGER')).toEqual([null, 'Organisateur']);
    expect(itemLabels('TRAVEL_MANAGER', null)).toEqual(['Voyages', 'Mes abonnements', 'Mes statistiques']);
    expect(itemLabels('TRAVEL_MANAGER', 'Organisateur')).toEqual([
      'Tableau de bord organisateur',
      'Mes voyages organisés',
    ]);
  });

  it('splits an ADMIN\'s entries into the flat section and the "Organisateur"/"Administration" groups', () => {
    expect(sectionLabels('ADMIN')).toEqual([null, 'Organisateur', 'Administration']);
    expect(itemLabels('ADMIN', null)).toEqual(['Voyages', 'Mes abonnements', 'Mes statistiques']);
    expect(itemLabels('ADMIN', 'Organisateur')).toEqual([
      'Tableau de bord organisateur',
      'Mes voyages organisés',
    ]);
    expect(itemLabels('ADMIN', 'Administration')).toEqual([
      'Tableau de bord admin',
      'Avis',
      'Signalements',
      'Utilisateurs',
      'Paiements',
      'Destinations',
    ]);
  });

  it('gives an empty flat section and no groups for a missing or unknown role', () => {
    expect(navSectionsFor(null)).toEqual([{ label: null, items: [] }]);
    expect(navSectionsFor('SUPERUSER')).toEqual([{ label: null, items: [] }]);
  });
});
