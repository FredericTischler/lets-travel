import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { AuthService, LoginResponse, RefreshResponse } from './auth.service';

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
  });

  it('login() stores the access and refresh tokens and exposes it as authenticated', () => {
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

  it('logout() clears both tokens, flips isAuthenticated to false, and revokes the refresh token', () => {
    const token = buildToken({ sub: 'user-1' });
    const response: LoginResponse = { id: 'user-1', email: 'admin@example.com', token, refreshToken: 'refresh-1' };

    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(response);

    expect(service.isAuthenticated()).toBe(true);

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    expect(service.getToken()).toBeNull();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();

    const logoutReq = httpMock.expectOne(`${environment.identityApiUrl}/logout`);
    expect(logoutReq.request.body).toEqual({ refreshToken: 'refresh-1' });
    logoutReq.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('logout() does not call the backend when there was no refresh token to revoke', () => {
    service.logout();

    httpMock.verify(); // no /logout request expected
  });

  it('logout() clears the local session even if revoking the refresh token fails', () => {
    const response: LoginResponse = {
      id: 'user-1',
      email: 'admin@example.com',
      token: buildToken({ sub: 'user-1' }),
      refreshToken: 'refresh-1',
    };
    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(response);

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    httpMock.expectOne(`${environment.identityApiUrl}/logout`).flush('boom', { status: 500, statusText: 'E' });
  });

  it('refreshAccessToken() exchanges the stored refresh token for a fresh pair', () => {
    const first: LoginResponse = {
      id: 'user-1',
      email: 'admin@example.com',
      token: buildToken({ sub: 'user-1' }),
      refreshToken: 'refresh-1',
    };
    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(first);

    const newToken = buildToken({ sub: 'user-1' });
    let result: RefreshResponse | undefined;
    service.refreshAccessToken().subscribe((res) => (result = res));

    const req = httpMock.expectOne(`${environment.identityApiUrl}/refresh`);
    expect(req.request.body).toEqual({ refreshToken: 'refresh-1' });
    req.flush({ token: newToken, refreshToken: 'refresh-2' });

    expect(result).toEqual({ token: newToken, refreshToken: 'refresh-2' });
    expect(service.getToken()).toBe(newToken);
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe('refresh-2');
  });

  it('refreshAccessToken() fails fast without a network call when there is no refresh token', () => {
    let errored = false;
    service.refreshAccessToken().subscribe({ error: () => (errored = true) });

    expect(errored).toBe(true);
    httpMock.verify(); // no request expected
  });

  it('refreshAccessToken() shares a single in-flight request across concurrent callers', () => {
    const response: LoginResponse = {
      id: 'user-1',
      email: 'admin@example.com',
      token: buildToken({ sub: 'user-1' }),
      refreshToken: 'refresh-1',
    };
    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(response);

    let firstResult: RefreshResponse | undefined;
    let secondResult: RefreshResponse | undefined;
    service.refreshAccessToken().subscribe((res) => (firstResult = res));
    service.refreshAccessToken().subscribe((res) => (secondResult = res));

    const newToken = buildToken({ sub: 'user-1' });
    httpMock.expectOne(`${environment.identityApiUrl}/refresh`).flush({ token: newToken, refreshToken: 'refresh-2' });

    expect(firstResult).toEqual({ token: newToken, refreshToken: 'refresh-2' });
    expect(secondResult).toEqual(firstResult);
  });

  it('getCurrentUserId() returns the sub claim of the token set via login()', () => {
    const token = buildToken({ sub: 'user-99' });
    const response: LoginResponse = { id: 'user-99', email: 'admin@example.com', token, refreshToken: 'r' };

    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(response);

    expect(service.getCurrentUserId()).toBe('user-99');
  });

  it('exposes the role and email claims of the stored token', () => {
    const token = buildToken({ sub: 'u1', email: 'm@example.com', role: 'TRAVEL_MANAGER' });

    service.login({ email: 'm@example.com', password: 'secret' }).subscribe();
    httpMock
      .expectOne(`${environment.identityApiUrl}/login`)
      .flush({ id: 'u1', email: 'm@example.com', token, refreshToken: 'r' });

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
      .flush({ id: 'u1', email: 'a@example.com', token, refreshToken: 'r' });

    expect(service.role()).toBeNull();
  });

  it('role() goes back to null on logout', () => {
    const token = buildToken({ sub: 'u1', role: 'TRAVELER' });
    service.login({ email: 'a@example.com', password: 'secret' }).subscribe();
    httpMock
      .expectOne(`${environment.identityApiUrl}/login`)
      .flush({ id: 'u1', email: 'a@example.com', token, refreshToken: 'r' });
    expect(service.role()).toBe('TRAVELER');

    service.logout();
    httpMock.expectOne(`${environment.identityApiUrl}/logout`).flush(null, { status: 204, statusText: 'No Content' });

    expect(service.role()).toBeNull();
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
