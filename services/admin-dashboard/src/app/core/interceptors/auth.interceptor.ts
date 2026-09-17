import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';

/** URLs of every backend API this dashboard talks to. */
const API_URLS = [environment.identityApiUrl, environment.paymentApiUrl, environment.travelApiUrl];

/**
 * Adds `Authorization: Bearer <token>` to every outgoing request targeting
 * one of this dashboard's backend APIs, and — since this project has no
 * refresh-token flow (see AuthService) — clears the stored token and
 * redirects to /login whenever such a request comes back 401 (token
 * missing, invalid or expired).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const isApiRequest = API_URLS.some((apiUrl) => req.url.startsWith(apiUrl));
  const token = authService.getToken();

  const authorizedReq = isApiRequest && token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authorizedReq).pipe(
    catchError((error: unknown) => {
      if (isApiRequest && error instanceof HttpErrorResponse && error.status === 401) {
        authService.logout();
        router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};