import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { ROLES } from '../auth/roles';
import { homeGuard, roleGuard } from './role.guard';

describe('role guards', () => {
  let authServiceStub: {
    isAuthenticated: () => boolean;
    role: () => string | null;
    logout: ReturnType<typeof vi.fn>;
  };
  let router: { parseUrl: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    authServiceStub = { isAuthenticated: () => true, role: () => null, logout: vi.fn() };
    router = { parseUrl: vi.fn((url: string) => ({ url }) as unknown as UrlTree) };

    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authServiceStub },
        { provide: Router, useValue: router },
      ],
    });
  });

  function run(guard: ReturnType<typeof roleGuard>) {
    return TestBed.runInInjectionContext(() => guard({} as never, {} as never));
  }

  function redirectedTo(result: unknown): string {
    return (result as unknown as { url: string }).url;
  }

  describe('roleGuard()', () => {
    it('redirects to /login without a token', () => {
      authServiceStub.isAuthenticated = () => false;

      expect(redirectedTo(run(roleGuard(ROLES.TRAVELER)))).toBe('/login');
    });

    it('lets a matching role through', () => {
      authServiceStub.role = () => 'TRAVEL_MANAGER';

      expect(run(roleGuard(ROLES.TRAVEL_MANAGER))).toBe(true);
    });

    it('lets ADMIN through every role restriction', () => {
      authServiceStub.role = () => 'ADMIN';

      expect(run(roleGuard(ROLES.TRAVELER))).toBe(true);
      expect(run(roleGuard(ROLES.TRAVEL_MANAGER))).toBe(true);
    });

    it('lets a TRAVEL_MANAGER through a TRAVELER screen (hierarchy)', () => {
      authServiceStub.role = () => 'TRAVEL_MANAGER';

      expect(run(roleGuard(ROLES.TRAVELER))).toBe(true);
    });

    it('sends a TRAVELER trying an admin screen back to their own home', () => {
      authServiceStub.role = () => 'TRAVELER';

      expect(redirectedTo(run(roleGuard(ROLES.ADMIN)))).toBe('/travels');
      expect(authServiceStub.logout).not.toHaveBeenCalled();
    });

    it('sends a TRAVEL_MANAGER trying an admin screen back to their own home', () => {
      authServiceStub.role = () => 'TRAVEL_MANAGER';

      expect(redirectedTo(run(roleGuard(ROLES.ADMIN)))).toBe('/manager/travels');
    });

    it('discards a token with no role and goes to /login', () => {
      authServiceStub.role = () => null;

      expect(redirectedTo(run(roleGuard(ROLES.TRAVELER)))).toBe('/login');
      expect(authServiceStub.logout).toHaveBeenCalledOnce();
    });

    it('discards a token with an unknown role and goes to /login', () => {
      authServiceStub.role = () => 'SUPERUSER';

      expect(redirectedTo(run(roleGuard(ROLES.TRAVELER)))).toBe('/login');
      expect(authServiceStub.logout).toHaveBeenCalledOnce();
    });
  });

  describe('homeGuard', () => {
    it.each([
      ['ADMIN', '/users'],
      ['TRAVEL_MANAGER', '/manager/travels'],
      ['TRAVELER', '/travels'],
    ])('redirects %s to %s', (role, expected) => {
      authServiceStub.role = () => role;

      expect(redirectedTo(run(homeGuard))).toBe(expected);
    });

    it('redirects to /login without a token', () => {
      authServiceStub.isAuthenticated = () => false;

      expect(redirectedTo(run(homeGuard))).toBe('/login');
      expect(authServiceStub.logout).not.toHaveBeenCalled();
    });

    it('discards a role-less token', () => {
      authServiceStub.role = () => null;

      expect(redirectedTo(run(homeGuard))).toBe('/login');
      expect(authServiceStub.logout).toHaveBeenCalledOnce();
    });
  });
});
