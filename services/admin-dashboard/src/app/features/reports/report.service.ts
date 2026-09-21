import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Lifecycle of a report; a report leaves OPEN once and is then immutable. */
export type ReportStatus = 'OPEN' | 'REVIEWED' | 'DISMISSED' | 'ACTIONED';

/** Statuses an admin may move an OPEN report to (PATCH /reports/{id}/status). */
export type ReportDecision = Exclude<ReportStatus, 'OPEN'>;

/**
 * Shape of the identity-service report resource
 * (ReportResponse.java). `reason` is free text written by a user: it must
 * only ever be rendered through interpolation, never as HTML.
 */
export interface Report {
  id: string;
  reporterId: string;
  reportedUserId: string;
  reason: string;
  status: ReportStatus;
  createdAt: string;
}

/** Maximum reason length accepted by the backend (`@Size(max = 2000)`). */
export const REPORT_REASON_MAX_LENGTH = 2000;

/** Access to the identity-service /reports endpoints. */
@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly http = inject(HttpClient);

  private readonly baseUrl = `${environment.identityApiUrl}/reports`;

  /** File a report against another user (any role; the reporter is the caller). */
  create(reportedUserId: string, reason: string): Observable<Report> {
    return this.http.post<Report>(this.baseUrl, { reportedUserId, reason });
  }

  /** Every active report — admin only. */
  list(): Observable<Report[]> {
    return this.http.get<Report[]>(this.baseUrl);
  }

  /** OPEN -> REVIEWED | DISMISSED | ACTIONED — admin only, 409 if already decided. */
  updateStatus(id: string, status: ReportDecision): Observable<Report> {
    return this.http.patch<Report>(`${this.baseUrl}/${id}/status`, { status });
  }

  /** Number of active reports filed against a user (any role). */
  countFor(userId: string): Observable<number> {
    return this.http
      .get<{ count: number }>(`${this.baseUrl}/count/${userId}`)
      .pipe(map((response) => response.count));
  }
}
