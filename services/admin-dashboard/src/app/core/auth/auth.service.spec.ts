import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { AuthService, LoginResponse } from './auth.service';

const TOKEN_STORAGE_KEY = 'admin-dashboard.jwt';

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
  });

  it('starts unauthenticated with no stored token', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.getToken()).toBeNull();
  });

  it('login() stores the returned token and exposes it as authenticated', () => {
    const token = buildToken({ sub: 'user-1' });
    const response: LoginResponse = { id: 'user-1', email: 'admin@example.com', token };

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

  it('logout() clears the token and flips isAuthenticated to false', () => {
    const token = buildToken({ sub: 'user-1' });
    const response: LoginResponse = { id: 'user-1', email: 'admin@example.com', token };

    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(response);

    expect(service.isAuthenticated()).toBe(true);

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    expect(service.getToken()).toBeNull();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('getCurrentUserId() returns the sub claim of the token set via login()', () => {
    const token = buildToken({ sub: 'user-99' });
    const response: LoginResponse = { id: 'user-99', email: 'admin@example.com', token };

    service.login({ email: 'admin@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush(response);

    expect(service.getCurrentUserId()).toBe('user-99');
  });

  it('exposes the role and email claims of the stored token', () => {
    const token = buildToken({ sub: 'u1', email: 'm@example.com', role: 'TRAVEL_MANAGER' });

    service.login({ email: 'm@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush({ id: 'u1', email: 'm@example.com', token });

    expect(service.role()).toBe('TRAVEL_MANAGER');
    expect(service.email()).toBe('m@example.com');
  });

  it('role() and email() are null without a token, and role() is null for a pre-Phase-1 token', () => {
    expect(service.role()).toBeNull();
    expect(service.email()).toBeNull();

    const token = buildToken({ sub: 'u1' });
    service.login({ email: 'a@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush({ id: 'u1', email: 'a@example.com', token });

    expect(service.role()).toBeNull();
  });

  it('role() goes back to null on logout', () => {
    const token = buildToken({ sub: 'u1', role: 'TRAVELER' });
    service.login({ email: 'a@example.com', password: 'secret' }).subscribe();
    httpMock.expectOne(`${environment.identityApiUrl}/login`).flush({ id: 'u1', email: 'a@example.com', token });
    expect(service.role()).toBe('TRAVELER');

    service.logout();

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
