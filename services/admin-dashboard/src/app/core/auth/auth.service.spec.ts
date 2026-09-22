import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { AuthService, LoginResponse } from './auth.service';

const TOKEN_STORAGE_KEY = 'admin-dashboard.jwt';
const REFRESH_TOKEN_STORAGE_KEY = 'admin-dashboard.refreshToken';

/**
 * Builds a syntactically valid (but unsigned) JWT carrying the given
 * payload, matching the shape AuthService.decodeJwtPayload expects.
 */
function buildToken(payload: Record<string, unknown>): string {
  const base64url = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

  return `${base64url({ alg: 'none', typ: 'JWT' })}.${base64url(payload)}.signature`;
}

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AuthService],
    });

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  });

  it('starts unauthenticated with no stored token', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.getToken()).toBeNull();
    expect(service.getRefreshToken()).toBeNull();
  });

  it('login() stores the returned token and refresh token, and exposes it as authenticated', () => {
    const token = buildToken({ sub: 'user-1' });
    const response: LoginResponse = {
      id: 'user-1',
      email: 'admin@example.com',
      token,
      refreshToken: 'refresh-1',
    };

    let result: LoginResponse | undefined;
    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe((res) => (result = res));

    const req = httpMock.expectOne(`${environment.identityApiUrl}/login`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'admin@example.com', password: 'secret' });
    req.flush(response);

    expect(result).toEqual(response);
    expect(service.isAuthenticated()).toBe(true);
    expect(service.getToken()).toBe(token);
    expect(service.getRefreshToken()).toBe('refresh-1');
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe(token);
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe('refresh-1');
  });

  it('picks up a previously stored token on construction (session restored on reload)', () => {
    const token = buildToken({ sub: 'user-42' });
    localStorage.setItem(TOKEN_STORAGE_KEY, token);

    // AuthService reads localStorage synchronously when instantiated, so a
    // fresh module/injector is needed to observe the value set above.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AuthService],
    });
    const restored = TestBed.inject(AuthService);

    expect(restored.isAuthenticated()).toBe(true);
    expect(restored.getToken()).toBe(token);
  });

  it('getCurrentUserId() returns null when there is no token', () => {
    expect(service.getCurrentUserId()).toBeNull();
  });

  it('logout() clears the token and refresh token, flips isAuthenticated to false, and best-effort revokes server-side', () => {
    const token = buildToken({ sub: 'user-1' });
    const response: LoginResponse = { id: 'user-1', email: 'admin@example.com', token, refreshToken: 'refresh-1' };

    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(response);

    expect(service.isAuthenticated()).toBe(true);

    service.logout();

    // Local state is cleared synchronously, before/regardless of the network call.
    expect(service.isAuthenticated()).toBe(false);
    expect(service.getToken()).toBeNull();
    expect(service.getRefreshToken()).toBeNull();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();

    const logoutReq = httpMock.expectOne(`${environment.identityApiUrl}/auth/logout`);
    expect(logoutReq.request.method).toBe('POST');
    expect(logoutReq.request.body).toEqual({ refreshToken: 'refresh-1' });
    // Best-effort: a network error on this call must not throw / break anything.
    logoutReq.flush('boom', { status: 500, statusText: 'Error' });
  });

  it('logout() does not call /auth/logout when there is no stored refresh token', () => {
    service.logout();

    httpMock.expectNone(`${environment.identityApiUrl}/auth/logout`);
  });

  it('getCurrentUserId() returns the sub claim of the token set via login()', () => {
    const token = buildToken({ sub: 'user-99' });
    const response: LoginResponse = {
      id: 'user-99',
      email: 'admin@example.com',
      token,
      refreshToken: 'refresh-99',
    };

    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(response);

    expect(service.getCurrentUserId()).toBe('user-99');
  });

  it('exposes the role and email claims of the stored token', () => {
    const token = buildToken({ sub: 'u1', email: 'm@example.com', role: 'TRAVEL_MANAGER' });

    service.login({ email: 'm@example.com', password: 'secret' }).subscribe();
    httpMock
      .expectOne(`${environment.identityApiUrl}/login`)
      .flush({ id: 'u1', email: 'm@example.com', token, refreshToken: 'r1' });

    expect(service.role()).toBe('TRAVEL_MANAGER');
    expect(service.email()).toBe('m@example.com');
  });

  it('role() and email() are null without a token, and role() is null for a pre-Phase-1 token', () => {
    expect(service.role()).toBeNull();
    expect(service.email()).toBeNull();

    const token = buildToken({ sub: 'u1' });
    service.login({ email: 'a@example.com', password: 'secret' }).subscribe();
    httpMock
      .expectOne(`${environment.identityApiUrl}/login`)
      .flush({ id: 'u1', email: 'a@example.com', token, refreshToken: 'r1' });

    expect(service.role()).toBeNull();
  });

  it('role() goes back to null on logout', () => {
    const token = buildToken({ sub: 'u1', role: 'TRAVELER' });
    service.login({ email: 'a@example.com', password: 'secret' }).subscribe();
    httpMock
      .expectOne(`${environment.identityApiUrl}/login`)
      .flush({ id: 'u1', email: 'a@example.com', token, refreshToken: 'r1' });
    expect(service.role()).toBe('TRAVELER');

    service.logout();
    httpMock.expectOne(`${environment.identityApiUrl}/auth/logout`).flush(null, { status: 204, statusText: 'No Content' });

    expect(service.role()).toBeNull();
  });

  describe('refresh()', () => {
    it('errors out synchronously (no HTTP call) when there is no stored refresh token', () => {
      let error: unknown;
      service.refresh().subscribe({ error: (err) => (error = err) });

      expect(error).toBeInstanceOf(Error);
      httpMock.expectNone(`${environment.identityApiUrl}/auth/refresh`);
    });

    it('POSTs the stored refresh token and replaces both stored values on success (rotation)', () => {
      const token = buildToken({ sub: 'u1' });
      service.login({ email: 'a@example.com', password: 'secret' }).subscribe();
      httpMock
        .expectOne(`${environment.identityApiUrl}/login`)
        .flush({ id: 'u1', email: 'a@example.com', token, refreshToken: 'refresh-1' });

      const newToken = buildToken({ sub: 'u1', role: 'TRAVELER' });
      let result: { accessToken: string; refreshToken: string } | undefined;
      service.refresh().subscribe((res) => (result = res));

      const req = httpMock.expectOne(`${environment.identityApiUrl}/auth/refresh`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ refreshToken: 'refresh-1' });
      req.flush({ accessToken: newToken, refreshToken: 'refresh-2' });

      expect(result).toEqual({ accessToken: newToken, refreshToken: 'refresh-2' });
      expect(service.getToken()).toBe(newToken);
      expect(service.getRefreshToken()).toBe('refresh-2');
      expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe(newToken);
      expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe('refresh-2');
    });

    it('propagates a failed refresh (e.g. 401: unknown/expired/revoked token) without touching stored state', () => {
      const token = buildToken({ sub: 'u1' });
      service.login({ email: 'a@example.com', password: 'secret' }).subscribe();
      httpMock
        .expectOne(`${environment.identityApiUrl}/login`)
        .flush({ id: 'u1', email: 'a@example.com', token, refreshToken: 'refresh-1' });

      let errored = false;
      service.refresh().subscribe({ error: () => (errored = true) });
      httpMock
        .expectOne(`${environment.identityApiUrl}/auth/refresh`)
        .flush({ error: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

      expect(errored).toBe(true);
      expect(service.getToken()).toBe(token);
      expect(service.getRefreshToken()).toBe('refresh-1');
    });
  });

  it('register() POSTs the credentials and the chosen role to the public /users, without logging in', () => {
    let result: unknown;
    service
      .register({ email: 'new@example.com', password: 'longenough', role: 'TRAVELER' })
      .subscribe((user) => (result = user));

    const req = httpMock.expectOne(`${environment.identityApiUrl}/users`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'new@example.com', password: 'longenough', role: 'TRAVELER' });
    const created = { id: 'u9', email: 'new@example.com', role: 'TRAVELER', createdAt: '2026-01-01T00:00:00Z' };
    req.flush(created);

    expect(result).toEqual(created);
    expect(service.isAuthenticated()).toBe(false);
  });
});
