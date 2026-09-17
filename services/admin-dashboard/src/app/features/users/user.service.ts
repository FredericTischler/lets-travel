import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * Shape of the identity-service GET/POST/PATCH /users response items.
 * See services/identity-service UserResponse.java.
 */
export interface User {
  id: string;
  email: string;
  createdAt: string;
}

/**
 * CRUD access to the identity-service /users endpoints. The auth
 * interceptor attaches the Bearer token automatically for every request
 * whose URL starts with environment.identityApiUrl.
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);

  list(): Observable<User[]> {
    return this.http.get<User[]>(`${environment.identityApiUrl}/users`);
  }

  create(email: string, password: string): Observable<User> {
    return this.http.post<User>(`${environment.identityApiUrl}/users`, { email, password });
  }

  update(id: string, email: string): Observable<User> {
    return this.http.patch<User>(`${environment.identityApiUrl}/users/${id}`, { email });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${environment.identityApiUrl}/users/${id}`);
  }
}