import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { environment } from '../../../environments/environment';

const TOKEN_STORAGE_KEY = 'admin-dashboard.jwt';

export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * Shape of the identity-service POST /login response.
 * See services/identity-service LoginResponse.java.
 */
export interface LoginResponse {
  id: string;
  email: string;
  token: string;
}

/**
 * Minimal auth state: holds the JWT issued by identity-service and exposes
 * it to the rest of the app (interceptor, guard).
 *
 * Storage is plain localStorage — no dedicated refresh-token flow, no
 * silent renewal: the backend JWT is short-lived (15 min, see
 * LoginResponse.java) and on expiry the user is simply redirected to
 * /login (see the auth interceptor's 401 handling).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly tokenSignal = signal<string | null>(
    localStorage.getItem(TOKEN_STORAGE_KEY),
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

  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${environment.identityApiUrl}/login`, request)
      .pipe(tap((response) => this.setToken(response.token)));
  }

  getToken(): string | null {
    return this.tokenSignal();
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

  logout(): void {
    this.setToken(null);
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
}