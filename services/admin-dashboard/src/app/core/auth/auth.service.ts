import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap, throwError } from 'rxjs';

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
 * Shape of the identity-service POST /login response. `refreshToken` is an
 * additive field (docs/lets-travel-architecture-decisions.md §11 addendum
 * "Refresh token") — `token` remains the 15 min access JWT, unchanged.
 * See services/identity-service LoginResponse.java.
 */
export interface LoginResponse {
  id: string;
  email: string;
  token: string;
  refreshToken: string;
}

/** Body/response of identity-service POST /auth/refresh (public, rotates the refresh token). */
export interface RefreshResponse {
  accessToken: string;
  refreshToken: string;
}

/**
 * Minimal auth state: holds the JWT issued by identity-service and exposes
 * it to the rest of the app (interceptor, guard).
 *
 * Storage is plain localStorage. The access JWT is short-lived (15 min, see
 * LoginResponse.java); a refresh token (opaque, `admin-dashboard.refreshToken`)
 * is stored alongside it so the auth interceptor can renew the session once
 * on a 401 instead of always redirecting to /login (see auth.interceptor.ts).
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
    return this.http.post<LoginResponse>(`${environment.identityApiUrl}/login`, request).pipe(
      tap((response) => {
        this.setToken(response.token);
        this.setRefreshToken(response.refreshToken);
      }),
    );
  }

  getToken(): string | null {
    return this.tokenSignal();
  }

  getRefreshToken(): string | null {
    return this.refreshTokenSignal();
  }

  /**
   * POST /auth/refresh with the stored refresh token. On success replaces
   * both the stored access token and refresh token (rotation: the backend
   * revokes the old refresh token, the one returned here is the only valid
   * one afterwards). Errors out synchronously (no HTTP call) when there is
   * no refresh token stored, so a caller (the auth interceptor) can tell
   * "nothing to refresh" apart from "the refresh call failed".
   */
  refresh(): Observable<RefreshResponse> {
    const refreshToken = this.refreshTokenSignal();
    if (!refreshToken) {
      return throwError(() => new Error('Aucun refresh token stocké : impossible de renouveler la session.'));
    }

    return this.http
      .post<RefreshResponse>(`${environment.identityApiUrl}/auth/refresh`, { refreshToken })
      .pipe(
        tap((response) => {
          this.setToken(response.accessToken);
          this.setRefreshToken(response.refreshToken);
        }),
      );
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
   * Clears the local session first (never blocked on the network — a local
   * logout must always succeed), then best-effort revokes the refresh token
   * server-side (POST /auth/logout, always 204, idempotent): fire-and-forget,
   * any network error is silently ignored, the caller is logged out locally
   * either way.
   */
  logout(): void {
    const refreshToken = this.refreshTokenSignal();
    this.setToken(null);
    this.setRefreshToken(null);

    if (refreshToken) {
      this.http
        .post(`${environment.identityApiUrl}/auth/logout`, { refreshToken })
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

  private setToken(token: string | null): void {
    this.tokenSignal.set(token);
    if (token) {
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
    } else {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
    }
  }

  private setRefreshToken(token: string | null): void {
    this.refreshTokenSignal.set(token);
    if (token) {
      localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, token);
    } else {
      localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
    }
  }
}