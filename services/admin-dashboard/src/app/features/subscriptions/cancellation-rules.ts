/**
 * Self-service unsubscribe cutoff of the subject ("3 days before the travel
 * start date"). Mirrors travel-service `SubscriptionService.CUTOFF_DAYS`; the
 * backend stays the authority (it answers 409), this only lets the UI warn
 * before the click.
 */
export const CANCELLATION_CUTOFF_DAYS = 3;

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/** Parses an ISO local date (`YYYY-MM-DD`) into a UTC-midnight timestamp. */
function toUtcDay(isoDate: string): number {
  const [year, month, day] = isoDate.split('-').map(Number);
  return Date.UTC(year, month - 1, day);
}

function todayUtcDay(now: Date): number {
  return Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
}

/**
 * True when fewer than {@link CANCELLATION_CUTOFF_DAYS} days remain before
 * `startDate` — the backend's rule is `startDate < today + 3 days` -> 409.
 */
export function isPastCancellationCutoff(startDate: string, now: Date = new Date()): boolean {
  return toUtcDay(startDate) < todayUtcDay(now) + CANCELLATION_CUTOFF_DAYS * MS_PER_DAY;
}

/** True when the travel has already started (subscribing is refused). */
export function hasStarted(startDate: string, now: Date = new Date()): boolean {
  return toUtcDay(startDate) < todayUtcDay(now);
}

/** Last day (ISO `YYYY-MM-DD`) on which a self-service unsubscribe is accepted. */
export function cancellationDeadline(startDate: string): string {
  return new Date(toUtcDay(startDate) - CANCELLATION_CUTOFF_DAYS * MS_PER_DAY)
    .toISOString()
    .slice(0, 10);
}
