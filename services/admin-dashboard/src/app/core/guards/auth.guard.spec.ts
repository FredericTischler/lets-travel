import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  let authServiceStub: { isAuthenticated: () => boolean };
  let router: Router;

  beforeEach(() => {
    authServiceStub = { isAuthenticated: () => false };

    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authServiceStub },
        { provide: Router, useValue: { parseUrl: vi.fn() } },
      ],
    });

    router = TestBed.inject(Router);
  });

  function runGuard() {
    return TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));
  }

  it('redirects to /login when there is no token', () => {
    const expectedUrlTree = {} as UrlTree;
    (router.parseUrl as ReturnType<typeof vi.fn>).mockReturnValue(expectedUrlTree);

    const result = runGuard();

    expect(router.parseUrl).toHaveBeenCalledWith('/login');
    expect(result).toBe(expectedUrlTree);
  });

  it('lets navigation through when a token is present (valid, non-expired)', () => {
    authServiceStub.isAuthenticated = () => true;

    const result = runGuard();

    expect(result).toBe(true);
    expect(router.parseUrl).not.toHaveBeenCalled();
  });

  it('lets navigation through even if the stored token has actually expired', () => {
    // authGuard only checks token *presence* (AuthService.isAuthenticated()),
    // it does not decode/verify expiry itself: an expired-but-present token
    // still reports isAuthenticated() === true here. Expiry is only caught
    // later, when the backend rejects the request with 401 and the auth
    // interceptor logs the user out (see auth.interceptor.spec.ts).
    authServiceStub.isAuthenticated = () => true;

    const result = runGuard();

    expect(result).toBe(true);
  });
});
