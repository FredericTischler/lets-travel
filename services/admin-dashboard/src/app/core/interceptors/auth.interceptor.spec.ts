import {
  HttpClient,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Observable, Subscriber } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let httpClient: HttpClient;
  let httpMock: HttpTestingController;
  let authServiceStub: {
    getToken: () => string | null;
    getRefreshToken: () => string | null;
    refresh: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  };
  let routerSpy: { navigate: ReturnType<typeof vi.fn> };

  /** Resolves the interceptor's `authService.refresh()` call by hand, like a real Observable would. */
  let refreshSubscriber: Subscriber<{ accessToken: string; refreshToken: string }> | null;

  beforeEach(() => {
    refreshSubscriber = null;
    authServiceStub = {
      getToken: () => 'valid-token',
      getRefreshToken: () => 'stored-refresh-token',
      refresh: vi.fn(
        () =>
          new Observable<{ accessToken: string; refreshToken: string }>((subscriber) => {
            refreshSubscriber = subscriber;
          }),
      ),
      logout: vi.fn(),
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

  it('logs the user out and redirects to /login on a 401 when there is no refresh token stored', () => {
    authServiceStub.getRefreshToken = () => null;
    let errored = false;

    httpClient.get(`${environment.paymentApiUrl}/payments`).subscribe({
      error: () => (errored = true),
    });

    const req = httpMock.expectOne(`${environment.paymentApiUrl}/payments`);
    req.flush({ error: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBe(true);
    expect(authServiceStub.refresh).not.toHaveBeenCalled();
    expect(authServiceStub.logout).toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('attempts a refresh and replays the original request with the new token, on a 401 when a refresh token is stored', () => {
    let result: unknown;
    httpClient.get(`${environment.paymentApiUrl}/payments`).subscribe({ next: (res) => (result = res) });

    const firstReq = httpMock.expectOne(`${environment.paymentApiUrl}/payments`);
    expect(firstReq.request.headers.get('Authorization')).toBe('Bearer valid-token');
    firstReq.flush({ error: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    expect(authServiceStub.refresh).toHaveBeenCalledTimes(1);

    // The service replaces the stored token as a side effect of a successful refresh.
    authServiceStub.getToken = () => 'new-token';
    refreshSubscriber!.next({ accessToken: 'new-token', refreshToken: 'new-refresh' });
    refreshSubscriber!.complete();

    const retriedReq = httpMock.expectOne(`${environment.paymentApiUrl}/payments`);
    expect(retriedReq.request.headers.get('Authorization')).toBe('Bearer new-token');
    retriedReq.flush(['ok']);

    expect(result).toEqual(['ok']);
    expect(authServiceStub.logout).not.toHaveBeenCalled();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('logs out (no second refresh attempt) when the refresh call itself fails', () => {
    let errored = false;
    httpClient.get(`${environment.paymentApiUrl}/payments`).subscribe({ error: () => (errored = true) });

    httpMock
      .expectOne(`${environment.paymentApiUrl}/payments`)
      .flush({}, { status: 401, statusText: 'Unauthorized' });

    refreshSubscriber!.error(new Error('refresh failed'));

    expect(errored).toBe(true);
    expect(authServiceStub.refresh).toHaveBeenCalledTimes(1);
    expect(authServiceStub.logout).toHaveBeenCalledTimes(1);
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('logs out when the refresh succeeds but the replayed request still fails', () => {
    let errored = false;
    httpClient.get(`${environment.paymentApiUrl}/payments`).subscribe({ error: () => (errored = true) });

    httpMock
      .expectOne(`${environment.paymentApiUrl}/payments`)
      .flush({}, { status: 401, statusText: 'Unauthorized' });

    refreshSubscriber!.next({ accessToken: 'new-token', refreshToken: 'new-refresh' });
    refreshSubscriber!.complete();

    httpMock
      .expectOne(`${environment.paymentApiUrl}/payments`)
      .flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBe(true);
    expect(authServiceStub.refresh).toHaveBeenCalledTimes(1);
    expect(authServiceStub.logout).toHaveBeenCalledTimes(1);
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('never attempts a second refresh when POST /auth/refresh itself answers 401 (breaks the loop)', () => {
    // A closer-to-real refresh(): it goes through the same HttpClient/interceptor as everything else.
    authServiceStub.refresh = vi.fn(() =>
      httpClient.post(`${environment.identityApiUrl}/auth/refresh`, { refreshToken: 'stored-refresh-token' }),
    );
    let errored = false;

    httpClient.get(`${environment.paymentApiUrl}/payments`).subscribe({ error: () => (errored = true) });
    httpMock
      .expectOne(`${environment.paymentApiUrl}/payments`)
      .flush({}, { status: 401, statusText: 'Unauthorized' });

    const refreshReq = httpMock.expectOne(`${environment.identityApiUrl}/auth/refresh`);
    refreshReq.flush({ error: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBe(true);
    expect(authServiceStub.refresh).toHaveBeenCalledTimes(1);
    expect(authServiceStub.logout).toHaveBeenCalledTimes(1);
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/login']);
    // No stray second refresh/replay request: afterEach's httpMock.verify() also covers this.
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
