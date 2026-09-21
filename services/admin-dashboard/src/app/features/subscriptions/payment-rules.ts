import { TravelerSubscription } from './subscription.service';

/** Time left before a pending payment expires, ready to display. */
export interface Remaining {
  expired: boolean;
  totalSeconds: number;
  /** `1 j 02 h`, `42 min 10 s`, `35 s`, or `expiré`. */
  label: string;
}

/**
 * Countdown to `expiresAt` (an ISO instant). A missing/unparseable deadline is
 * reported as not expired with an empty label: the backend is the authority
 * (it derives EXPIRED at read time), this only helps the traveler see the clock.
 */
export function remainingTime(expiresAt: string | null | undefined, now: Date = new Date()): Remaining {
  if (!expiresAt) {
    return { expired: false, totalSeconds: Number.POSITIVE_INFINITY, label: '' };
  }
  const deadline = Date.parse(expiresAt);
  if (Number.isNaN(deadline)) {
    return { expired: false, totalSeconds: Number.POSITIVE_INFINITY, label: '' };
  }
  const totalSeconds = Math.floor((deadline - now.getTime()) / 1000);
  if (totalSeconds <= 0) {
    return { expired: true, totalSeconds: 0, label: 'expiré' };
  }
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  let label: string;
  if (days > 0) {
    label = `${days} j ${pad(hours)} h`;
  } else if (hours > 0) {
    label = `${hours} h ${pad(minutes)} min`;
  } else if (minutes > 0) {
    label = `${minutes} min ${pad(seconds)} s`;
  } else {
    label = `${seconds} s`;
  }
  return { expired: false, totalSeconds, label };
}

/**
 * The PayPal order id carried by an approval URL (`…/checkoutnow?token=<orderId>`),
 * which is also what PayPal appends as `token` when it sends the payer back.
 * `null` when the URL is malformed or has no token.
 */
export function extractPayPalOrderId(approveUrl: string | null | undefined): string | null {
  if (!approveUrl) {
    return null;
  }
  try {
    return new URL(approveUrl).searchParams.get('token');
  } catch {
    return null;
  }
}

/**
 * True for an https URL on paypal.com (or a subdomain, e.g. sandbox.paypal.com).
 * The approval URL comes from the backend, but the browser is about to navigate
 * to it: refusing anything else keeps a tampered response from redirecting the
 * payer to an arbitrary site.
 */
export function isTrustedPayPalUrl(url: string | null | undefined): boolean {
  if (!url) {
    return false;
  }
  try {
    const parsed = new URL(url);
    return (
      parsed.protocol === 'https:' &&
      (parsed.hostname === 'paypal.com' || parsed.hostname.endsWith('.paypal.com'))
    );
  } catch {
    return false;
  }
}

/**
 * The subscription that currently matters for one travel out of the caller's
 * history: an ACTIVE one, else a PENDING_PAYMENT that has not run out of time
 * (newest first). CANCELLED and EXPIRED rows are history, not a current state.
 */
export function currentSubscription(
  rows: readonly TravelerSubscription[],
  destinationId: string,
  now: Date = new Date(),
): TravelerSubscription | null {
  const mine = rows
    .filter((row) => row.destinationId === destinationId)
    .sort((a, b) => b.subscribedAt.localeCompare(a.subscribedAt));
  const active = mine.find((row) => row.status === 'ACTIVE');
  if (active) {
    return active;
  }
  return (
    mine.find((row) => row.status === 'PENDING_PAYMENT' && !remainingTime(row.expiresAt, now).expired) ??
    null
  );
}
