import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';

/** URLs of every backend API this dashboard talks to. */
const API_URLS = [environment.identityApiUrl, environment.paymentApiUrl, environment.travelApiUrl];

/** The refresh endpoint itself, to never attempt a second refresh on its own 401. */
const REFRESH_URL = `${environment.identityApiUrl}/auth/refresh`;

/**
 * Adds `Authorization: Bearer <token>` to every outgoing request targeting
 * one of this dashboard's backend APIs. On a 401 from one of them, tries a
 * single silent renewal (docs/lets-travel-architecture-decisions.md §11
 * addendum "Refresh token") before giving up:
 *
 * - No refresh token stored (never logged in with one, or already spent) ->
 *   straight to logout+redirect, no network call.
 * - A refresh token is stored -> `authService.refresh()` once; on success the
 *   original request is replayed with the new access token; on failure
 *   (including the refresh call's own 401) -> logout+redirect, never a
 *   second refresh attempt.
 * - A 401 from `POST /auth/refresh` itself is never retried: it goes
 *   straight to the failure branch above, which is what breaks the loop.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const isApiRequest = API_URLS.some((apiUrl) => req.url.startsWith(apiUrl));
  const isRefreshRequest = req.url === REFRESH_URL;
  const token = authService.getToken();

  const authorizedReq = isApiRequest && token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  const logoutAndRedirect = () => {
    authService.logout();
    router.navigate(['/login']);
  };

  return next(authorizedReq).pipe(
    catchError((error: unknown) => {
      const isAuthFailure = isApiRequest && error instanceof HttpErrorResponse && error.status === 401;
      if (!isAuthFailure) {
        return throwError(() => error);
      }

      // The refresh call itself failed: no second attempt, straight to logout
      // (this is what stops an infinite refresh loop).
      if (isRefreshRequest) {
        return throwError(() => error);
      }

      if (!authService.getRefreshToken()) {
        logoutAndRedirect();
        return throwError(() => error);
      }

      return authService.refresh().pipe(
        switchMap(() =>
          next(req.clone({ setHeaders: { Authorization: `Bearer ${authService.getToken()}` } })),
        ),
        catchError((refreshOrRetryError: unknown) => {
          logoutAndRedirect();
          return throwError(() => refreshOrRetryError);
        }),
      );
    }),
  );
};