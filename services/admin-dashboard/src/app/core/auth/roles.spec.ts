import { ROLES, SIGN_UP_ROLES, hasAccess, homeRouteFor, impliedRoles, isRole } from './roles';

describe('roles', () => {
  it('isRole() only accepts the three known roles', () => {
    expect(isRole('ADMIN')).toBe(true);
    expect(isRole('TRAVEL_MANAGER')).toBe(true);
    expect(isRole('TRAVELER')).toBe(true);
    expect(isRole('admin')).toBe(false);
    expect(isRole('SUPERUSER')).toBe(false);
    expect(isRole(null)).toBe(false);
    expect(isRole(undefined)).toBe(false);
  });

  it('impliedRoles() follows the subject hierarchy', () => {
    expect(impliedRoles(ROLES.ADMIN)).toEqual(['ADMIN', 'TRAVEL_MANAGER', 'TRAVELER']);
    expect(impliedRoles(ROLES.TRAVEL_MANAGER)).toEqual(['TRAVEL_MANAGER', 'TRAVELER']);
    expect(impliedRoles(ROLES.TRAVELER)).toEqual(['TRAVELER']);
  });

  describe('hasAccess()', () => {
    it('lets ADMIN through everything', () => {
      expect(hasAccess('ADMIN', [ROLES.ADMIN])).toBe(true);
      expect(hasAccess('ADMIN', [ROLES.TRAVEL_MANAGER])).toBe(true);
      expect(hasAccess('ADMIN', [ROLES.TRAVELER])).toBe(true);
    });

    it('lets TRAVEL_MANAGER through manager and traveler screens but not admin ones', () => {
      expect(hasAccess('TRAVEL_MANAGER', [ROLES.TRAVEL_MANAGER])).toBe(true);
      expect(hasAccess('TRAVEL_MANAGER', [ROLES.TRAVELER])).toBe(true);
      expect(hasAccess('TRAVEL_MANAGER', [ROLES.ADMIN])).toBe(false);
    });

    it('keeps TRAVELER out of manager and admin screens', () => {
      expect(hasAccess('TRAVELER', [ROLES.TRAVELER])).toBe(true);
      expect(hasAccess('TRAVELER', [ROLES.TRAVEL_MANAGER])).toBe(false);
      expect(hasAccess('TRAVELER', [ROLES.ADMIN])).toBe(false);
    });

    it('never grants access to a missing or unknown role', () => {
      expect(hasAccess(null, [ROLES.TRAVELER])).toBe(false);
      expect(hasAccess('SUPERUSER', [ROLES.TRAVELER, ROLES.ADMIN])).toBe(false);
    });
  });

  it('homeRouteFor() gives each role a landing page and unknown roles none', () => {
    expect(homeRouteFor('ADMIN')).toBe('/users');
    expect(homeRouteFor('TRAVEL_MANAGER')).toBe('/manager/travels');
    expect(homeRouteFor('TRAVELER')).toBe('/travels');
    expect(homeRouteFor(null)).toBeNull();
    expect(homeRouteFor('SUPERUSER')).toBeNull();
  });

  it('never offers ADMIN on sign-up', () => {
    expect(SIGN_UP_ROLES).toEqual(['TRAVELER', 'TRAVEL_MANAGER']);
    expect(SIGN_UP_ROLES).not.toContain('ADMIN');
  });
});
