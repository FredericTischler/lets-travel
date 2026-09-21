import { NAV_ITEMS, navItemsFor } from './nav-items';

describe('navItemsFor()', () => {
  const labels = (role: string | null) => navItemsFor(role).map((item) => item.label);

  it('gives a TRAVELER only the traveler entries', () => {
    expect(labels('TRAVELER')).toEqual(['Voyages', 'Mes abonnements']);
  });

  it('gives a TRAVEL_MANAGER the manager entries on top of the traveler ones', () => {
    expect(labels('TRAVEL_MANAGER')).toEqual(['Voyages', 'Mes abonnements', 'Mes voyages organisés']);
  });

  it('gives an ADMIN every entry (hierarchy)', () => {
    expect(navItemsFor('ADMIN')).toEqual([...NAV_ITEMS]);
  });

  it('shows nothing for a missing or unknown role', () => {
    expect(navItemsFor(null)).toEqual([]);
    expect(navItemsFor('SUPERUSER')).toEqual([]);
  });

  it('only points to routes starting with a slash', () => {
    for (const item of NAV_ITEMS) {
      expect(item.route.startsWith('/')).toBe(true);
    }
  });
});
