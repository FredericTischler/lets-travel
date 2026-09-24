import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, finalize, shareReplay, tap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Role } from './roles';

const TOKEN_STORAGE_KEY = 'admin-dashboard.jwt';
const REFRESH_TOKEN_STORAGE_KEY = 'admin-dashboard.refreshToken';

export interface LoginRequest {
  email: string;
  password: string;
}

/** Body of the public POST /users (see identity-service CreateUserRequest.java). */
export interface RegisterRequest {
  email: string;
  password: string;
  role: Role;
}

/** POST /users response (identity-service UserResponse.java). */
export interface RegisteredUser {
  id: string;
  email: string;
  role: string;
  createdAt: string;
}

/**
 * Shape of the identity-service POST /login response.
 * See services/identity-service LoginResponse.java. `refreshToken` is a
 * separate, longer-lived opaque credential (7 days) usable exactly once
 * against POST /refresh — never a JWT, never decoded client-side.
 */
export interface LoginResponse {
  id: string;
  email: string;
  token: string;
  refreshToken: string;
}

/** Shape of the identity-service POST /refresh response (RefreshResponse.java). */
export interface RefreshResponse {
  token: string;
  refreshToken: string;
}

/**
 * Auth state: holds the access JWT and the refresh token issued by
 * identity-service, both in plain localStorage, and exposes the access
 * token to the rest of the app (interceptor, guard).
 *
 * The access token is still short-lived (15 min, see LoginResponse.java) —
 * but its expiry is now transparent to the user: {@link refreshAccessToken}
 * (called by the auth interceptor on a 401) exchanges the refresh token for
 * a fresh pair without asking for credentials again. Only once the refresh
 * token itself is invalid (expired after 7 days, already used, or revoked
 * by {@link logout}) does the interceptor fall back to redirecting to
 * /login — see auth.interceptor.ts.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly tokenSignal = signal<string | null>(
    localStorage.getItem(TOKEN_STORAGE_KEY),
  );
  private readonly refreshTokenSignal = signal<string | null>(
    localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY),
  );

  /** Shared in-flight POST /refresh, so concurrent 401s trigger only one request. */
  private refreshInFlight$: Observable<RefreshResponse> | null = null;

  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);

  /**
   * The `role` claim of the stored JWT (see identity-service JwtService —
   * `ADMIN` | `TRAVEL_MANAGER` | `TRAVELER` since
   * docs/lets-travel-architecture-decisions.md §1), or `null` if there is no
   * token or it carries no role claim (pre-Phase-1 token).
   */
  readonly role = computed(() => {
    const token = this.tokenSignal();
    if (!token) {
      return null;
    }
    const claims = this.decodeJwtPayload(token);
    return typeof claims?.['role'] === 'string' ? (claims['role'] as string) : null;
  });

  /** The `email` claim of the stored JWT, or `null` (display only). */
  readonly email = computed(() => {
    const token = this.tokenSignal();
    if (!token) {
      return null;
    }
    const claims = this.decodeJwtPayload(token);
    return typeof claims?.['email'] === 'string' ? (claims['email'] as string) : null;
  });

  /**
   * Public sign-up: POST /users (unauthenticated on the backend). The caller
   * chooses the role — the sign-up form only ever offers TRAVELER or
   * TRAVEL_MANAGER (see SIGN_UP_ROLES). This does not log the user in.
   */
  register(request: RegisterRequest): Observable<RegisteredUser> {
    return this.http.post<RegisteredUser>(`${environment.identityApiUrl}/users`, request);
  }

  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${environment.identityApiUrl}/login`, request)
      .pipe(tap((response) => this.setTokens(response.token, response.refreshToken)));
  }

  getToken(): string | null {
    return this.tokenSignal();
  }

  /**
   * Exchanges the stored refresh token for a fresh access/refresh pair.
   * Called by the auth interceptor when an API request comes back 401 — not
   * meant to be called directly by feature code. Concurrent callers (several
   * requests failing around the same time) share the same in-flight HTTP
   * call rather than each redeeming the refresh token (which is single-use:
   * a second, independent call would find it already rotated and fail).
   *
   * @throws (via the returned Observable) if there is no refresh token
   *         stored, or the backend rejects it (expired/already used/revoked)
   */
  refreshAccessToken(): Observable<RefreshResponse> {
    if (this.refreshInFlight$) {
      return this.refreshInFlight$;
    }

    const refreshToken = this.refreshTokenSignal();
    if (!refreshToken) {
      return throwError(() => new Error('No refresh token available'));
    }

    const request$ = this.http
      .post<RefreshResponse>(`${environment.identityApiUrl}/refresh`, { refreshToken })
      .pipe(
        tap((response) => this.setTokens(response.token, response.refreshToken)),
        finalize(() => {
          this.refreshInFlight$ = null;
        }),
        shareReplay(1),
      );
    this.refreshInFlight$ = request$;
    return request$;
  }

  /**
   * Returns the id of the currently logged-in user, read from the `sub`
   * claim of the stored JWT (see identity-service JwtService#generateToken:
   * subject = user id). Returns null if there is no token or it cannot be
   * decoded.
   */
  getCurrentUserId(): string | null {
    const token = this.tokenSignal();
    if (!token) {
      return null;
    }

    const claims = this.decodeJwtPayload(token);
    return typeof claims?.['sub'] === 'string' ? claims['sub'] : null;
  }

  /**
   * Clears the local session immediately, and best-effort revokes the
   * refresh token server-side (POST /logout — fire-and-forget: whether it
   * succeeds or not, the local session is already gone, and an already
   * expired/unknown refresh token is a normal 204 on that endpoint anyway).
   */
  logout(): void {
    const refreshToken = this.refreshTokenSignal();
    this.setTokens(null, null);
    if (refreshToken) {
      this.http
        .post(`${environment.identityApiUrl}/logout`, { refreshToken })
        .subscribe({ error: () => undefined });
    }
  }

  /**
   * Minimal JWT payload decoding (base64url -> JSON) — no signature
   * verification, which is fine here since this only reads a claim already
   * trusted by the backend that issued and will re-verify the token on
   * every request.
   */
  private decodeJwtPayload(token: string): Record<string, unknown> | null {
    const parts = token.split('.');
    if (parts.length !== 3) {
      return null;
    }

    try {
      const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      const json = decodeURIComponent(
        atob(base64)
          .split('')
          .map((char) => '%' + char.charCodeAt(0).toString(16).padStart(2, '0'))
          .join(''),
      );
      return JSON.parse(json) as Record<string, unknown>;
    } catch {
      return null;
    }
  }

  private setTokens(token: string | null, refreshToken: string | null): void {
    this.tokenSignal.set(token);
    this.refreshTokenSignal.set(refreshToken);
    if (token) {
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
    } else {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
    }
    if (refreshToken) {
      localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, refreshToken);
    } else {
      localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
    }
  }
}
