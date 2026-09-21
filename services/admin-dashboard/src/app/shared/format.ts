/**
 * Display helpers shared by the dashboards. Pure functions, no Angular: money
 * and ratings are formatted here once so every screen shows them the same way.
 *
 * Money is a per-currency map on the wire (`{ "EUR": 1200.5, "USD": 30 }`):
 * currencies are never summed, so {@link formatMoneyMap} lists them side by
 * side. A scalar `amount` is the reference-currency share only.
 */

const LOCALE = 'fr-FR';

/** `1 234,50 €` — falls back to `1234.50 XXX` for a code Intl does not know. */
export function formatMoney(amount: number | string | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) {
    return '—';
  }
  const value = Number(amount);
  if (!Number.isFinite(value)) {
    return '—';
  }
  try {
    return new Intl.NumberFormat(LOCALE, { style: 'currency', currency }).format(value);
  } catch {
    return `${value.toFixed(2)} ${currency}`;
  }
}

/** Every currency of a per-currency map, `—` when the map is null or empty. */
export function formatMoneyMap(totals: Record<string, number> | null | undefined): string {
  if (!totals) {
    return '—';
  }
  const entries = Object.entries(totals);
  if (entries.length === 0) {
    return '—';
  }
  return entries.map(([currency, amount]) => formatMoney(amount, currency)).join(' · ');
}

/** `4,3 / 5`, or `—` when there is no feedback yet (a missing rating is not a zero). */
export function formatRating(average: number | null | undefined): string {
  if (average === null || average === undefined) {
    return '—';
  }
  return `${new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 2 }).format(average)} / 5`;
}

/** `sept. 26` from `2026-09` (short: it labels chart columns). Unparseable input is returned as is. */
export function formatMonth(month: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(month);
  if (!match) {
    return month;
  }
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, 1));
  return new Intl.DateTimeFormat(LOCALE, { month: 'short', year: '2-digit', timeZone: 'UTC' }).format(
    date,
  );
}

/** Plain number, 2 decimals at most (scores, counts); `—` when missing. */
export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 2 }).format(value);
}

/** Travel status of a dashboard row (`TravelStatsRow.status`). */
export const TRAVEL_STATUS_LABELS: Record<string, string> = {
  UPCOMING: 'À venir',
  ONGOING: 'En cours',
  PAST: 'Terminé',
};
