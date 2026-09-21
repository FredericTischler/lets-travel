import { HttpErrorResponse } from '@angular/common/http';

import { extractErrorMessage } from './http-error';

describe('extractErrorMessage()', () => {
  it("returns the backend's error message when there is one", () => {
    const err = new HttpErrorResponse({ status: 409, error: { error: 'Already subscribed', status: 409 } });

    expect(extractErrorMessage(err, 'fallback')).toBe('Already subscribed');
  });

  it('falls back when the body has no string `error`', () => {
    expect(extractErrorMessage(new HttpErrorResponse({ status: 500, error: 'boom' }), 'fallback')).toBe(
      'fallback',
    );
    expect(extractErrorMessage(new HttpErrorResponse({ status: 500, error: { error: 42 } }), 'fallback')).toBe(
      'fallback',
    );
    expect(extractErrorMessage(new HttpErrorResponse({ status: 0 }), 'fallback')).toBe('fallback');
  });

  it('falls back for anything that is not an HttpErrorResponse', () => {
    expect(extractErrorMessage(new Error('x'), 'fallback')).toBe('fallback');
    expect(extractErrorMessage(null, 'fallback')).toBe('fallback');
  });
});
