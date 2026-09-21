import {
  CANCELLATION_CUTOFF_DAYS,
  cancellationDeadline,
  hasStarted,
  isPastCancellationCutoff,
} from './cancellation-rules';

describe('cancellation rules', () => {
  // Fixed "now": 2026-09-21, midday local time.
  const now = new Date(2026, 8, 21, 12, 0, 0);

  it('uses the 3-day cutoff of the subject', () => {
    expect(CANCELLATION_CUTOFF_DAYS).toBe(3);
  });

  it('accepts a cancellation exactly 3 days before the start (backend: start >= today + 3)', () => {
    expect(isPastCancellationCutoff('2026-09-24', now)).toBe(false);
  });

  it('refuses a cancellation less than 3 days before the start', () => {
    expect(isPastCancellationCutoff('2026-09-23', now)).toBe(true);
    expect(isPastCancellationCutoff('2026-09-21', now)).toBe(true);
  });

  it('accepts a cancellation far ahead of the start', () => {
    expect(isPastCancellationCutoff('2027-01-10', now)).toBe(false);
  });

  it('flags a travel that already started', () => {
    expect(hasStarted('2026-09-20', now)).toBe(true);
    expect(hasStarted('2026-09-21', now)).toBe(false);
    expect(hasStarted('2026-12-01', now)).toBe(false);
  });

  it('computes the last day on which unsubscribing is accepted', () => {
    expect(cancellationDeadline('2026-10-10')).toBe('2026-10-07');
    // Across a month boundary.
    expect(cancellationDeadline('2026-11-02')).toBe('2026-10-30');
  });
});
