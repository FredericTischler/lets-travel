import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';

/** URLs of every backend API this dashboard talks to. */
const API_URLS = [environment.identityApiUrl, environment.paymentApiUrl, environment.travelApiUrl];

/**
 * The auth endpoints themselves: a 401 from one of these is never "the
 * access token expired, try a silent refresh" — it is either wrong
 * credentials (`/login`) or the refresh token itself being invalid
 * (`/refresh`, `/logout`). Retrying those through the refresh flow would at
 * best do nothing, at worst loop.
 */
const AUTH_ENDPOINTS = [
  `${environment.identityApiUrl}/login`,
  `${environment.identityApiUrl}/refresh`,
  `${environment.identityApiUrl}/logout`,
];

/**
 * Adds `Authorization: Bearer <token>` to every outgoing request targeting
 * one of this dashboard's backend APIs. On a 401 from one of those (the
 * access token expired or was otherwise rejected), attempts one silent
 * refresh (`AuthService#refreshAccessToken`, shared across concurrent 401s)
 * and retries the original request with the fresh token; only if that
 * refresh itself fails does it clear the stored session and redirect to
 * /login — same fallback this project always had, now the last resort
 * instead of the only behaviour.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const isApiRequest = API_URLS.some((apiUrl) => req.url.startsWith(apiUrl));
  const isAuthEndpoint = AUTH_ENDPOINTS.includes(req.url);
  const token = authService.getToken();

  const authorizedReq = isApiRequest && token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authorizedReq).pipe(
    catchError((error: unknown) => {
      const isUnauthorized =
        isApiRequest && error instanceof HttpErrorResponse && error.status === 401;
      if (!isUnauthorized || isAuthEndpoint) {
        return throwError(() => error);
      }

      return authService.refreshAccessToken().pipe(
        switchMap((refreshed) =>
          next(req.clone({ setHeaders: { Authorization: `Bearer ${refreshed.token}` } })),
        ),
        catchError(() => {
          authService.logout();
          router.navigate(['/login']);
          return throwError(() => error);
        }),
      );
    }),
  );
};
