import { HttpErrorResponse } from '@angular/common/http';

/**
 * Message to show for a failed API call: the backend's own `{ "error": "..." }`
 * body when it sent one (all three services share that shape), otherwise the
 * caller's French fallback. The result is always rendered through Angular
 * interpolation, never as HTML.
 */
export function extractErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse && typeof err.error?.error === 'string') {
    return err.error.error;
  }
  return fallback;
}
