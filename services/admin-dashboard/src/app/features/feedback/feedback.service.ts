import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * One feedback (travel-service FeedbackResponse.java), the same shape on every
 * endpoint. `comment` is plain text written by another user: it must only ever
 * be rendered through interpolation, never as HTML.
 */
export interface Feedback {
  id: string;
  travelerId: string;
  destinationId: string;
  destinationName: string;
  destinationCountry: string;
  destinationEndDate: string;
  /** Integer 1..5. */
  rating: number;
  comment: string | null;
  createdAt: string;
}

/** Bounds enforced by the backend (`GiveFeedbackRequest`). */
export const FEEDBACK_COMMENT_MAX_LENGTH = 1000;
export const FEEDBACK_MIN_RATING = 1;
export const FEEDBACK_MAX_RATING = 5;

/** Access to the travel-service feedback endpoints. */
@Injectable({ providedIn: 'root' })
export class FeedbackService {
  private readonly http = inject(HttpClient);

  private readonly base = environment.travelApiUrl;

  /**
   * Rate a travel the caller took part in. 403: no ACTIVE subscription; 409:
   * the travel has not ended yet, or feedback was already given (one per
   * traveler and travel, immutable). The author is the token subject — never sent.
   * A blank comment is omitted: the backend refuses a present-but-blank one.
   */
  give(destinationId: string, rating: number, comment: string | null): Observable<Feedback> {
    const trimmed = comment?.trim() ?? '';
    const body: { rating: number; comment?: string } = { rating };
    if (trimmed !== '') {
      body.comment = trimmed;
    }
    return this.http.post<Feedback>(`${this.base}/destinations/${destinationId}/feedback`, body);
  }

  /** Feedback on one travel — owning manager or admin only (quality control). */
  forDestination(destinationId: string): Observable<Feedback[]> {
    return this.http.get<Feedback[]>(`${this.base}/destinations/${destinationId}/feedback`);
  }

  /** The caller's own feedback ("vos avis"). */
  mine(): Observable<Feedback[]> {
    return this.http.get<Feedback[]>(`${this.base}/travelers/me/feedback`);
  }

  /** Every feedback of the platform — admin only. */
  all(): Observable<Feedback[]> {
    return this.http.get<Feedback[]>(`${this.base}/feedback`);
  }
}
