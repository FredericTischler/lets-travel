import {
  HttpClient,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Observable, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService, RefreshResponse } from '../auth/auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let httpClient: HttpClient;
  let httpMock: HttpTestingController;
  let authServiceStub: {
    getToken: () => string | null;
    logout: ReturnType<typeof vi.fn>;
    refreshAccessToken: ReturnType<typeof vi.fn>;
  };
  let routerSpy: { navigate: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    authServiceStub = {
      getToken: () => 'valid-token',
      logout: vi.fn(),
      refreshAccessToken: vi.fn(),
    };
    routerSpy = { navigate: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authServiceStub },
        { provide: Router, useValue: routerSpy },
      ],
    });

    httpClient = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('adds an Authorization: Bearer <token> header on requests to a known API', () => {
    httpClient.get(`${environment.travelApiUrl}/destinations`).subscribe();

    const req = httpMock.expectOne(`${environment.travelApiUrl}/destinations`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer valid-token');
    req.flush([]);
  });

  it('does not add an Authorization header on requests to a non-API URL', () => {
    httpClient.get('https://unrelated.example.com/ping').subscribe();

    const req = httpMock.expectOne('https://unrelated.example.com/ping');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('does not add an Authorization header when there is no stored token', () => {
    authServiceStub.getToken = () => null;

    httpClient.get(`${environment.identityApiUrl}/users`).subscribe();

    const req = httpMock.expectOne(`${environment.identityApiUrl}/users`);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush([]);
  });

  it('silently refreshes and retries the original request on a 401 from a known API', () => {
    const refreshed: RefreshResponse = { token: 'new-token', refreshToken: 'new-refresh' };
    authServiceStub.refreshAccessToken.mockReturnValue(new Observable<RefreshResponse>((sub) => {
      sub.next(refreshed);
      sub.complete();
    }));

    let result: unknown;
    httpClient.get(`${environment.paymentApiUrl}/payments`).subscribe((res) => (result = res));

    const firstAttempt = httpMock.expectOne(`${environment.paymentApiUrl}/payments`);
    firstAttempt.flush({ error: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    expect(authServiceStub.refreshAccessToken).toHaveBeenCalled();
    const retry = httpMock.expectOne(`${environment.paymentApiUrl}/payments`);
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new-token');
    retry.flush([{ ok: true }]);

    expect(result).toEqual([{ ok: true }]);
    expect(authServiceStub.logout).not.toHaveBeenCalled();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('logs the user out and redirects to /login when the silent refresh itself fails', () => {
    authServiceStub.refreshAccessToken.mockReturnValue(throwError(() => new Error('refresh token expired')));

    let errored = false;
    httpClient.get(`${environment.paymentApiUrl}/payments`).subscribe({
      error: () => (errored = true),
    });

    const req = httpMock.expectOne(`${environment.paymentApiUrl}/payments`);
    req.flush({ error: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBe(true);
    expect(authServiceStub.logout).toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('does not attempt a refresh for a 401 from /login itself (wrong credentials, not an expired session)', () => {
    let errored = false;
    httpClient
      .post(`${environment.identityApiUrl}/login`, { email: 'a@a.com', password: 'wrong' })
      .subscribe({ error: () => (errored = true) });

    const req = httpMock.expectOne(`${environment.identityApiUrl}/login`);
    req.flush({ error: 'Invalid credentials' }, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBe(true);
    expect(authServiceStub.refreshAccessToken).not.toHaveBeenCalled();
    expect(authServiceStub.logout).not.toHaveBeenCalled();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('does not attempt a refresh for a 401 from /refresh itself', () => {
    let errored = false;
    httpClient
      .post(`${environment.identityApiUrl}/refresh`, { refreshToken: 'x' })
      .subscribe({ error: () => (errored = true) });

    const req = httpMock.expectOne(`${environment.identityApiUrl}/refresh`);
    req.flush({ error: 'Invalid or missing token' }, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBe(true);
    expect(authServiceStub.refreshAccessToken).not.toHaveBeenCalled();
  });

  it('does not log out or redirect on a non-401 error', () => {
    httpClient.get(`${environment.paymentApiUrl}/payments`).subscribe({ error: () => {} });

    const req = httpMock.expectOne(`${environment.paymentApiUrl}/payments`);
    req.flush({ error: 'Server error' }, { status: 500, statusText: 'Internal Server Error' });

    expect(authServiceStub.logout).not.toHaveBeenCalled();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('does not log out on a 401 from a non-API URL', () => {
    httpClient.get('https://unrelated.example.com/ping').subscribe({ error: () => {} });

    const req = httpMock.expectOne('https://unrelated.example.com/ping');
    req.flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(authServiceStub.logout).not.toHaveBeenCalled();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });
});
