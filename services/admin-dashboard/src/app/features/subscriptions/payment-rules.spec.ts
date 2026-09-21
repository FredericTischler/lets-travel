import {
  currentSubscription,
  extractPayPalOrderId,
  isTrustedPayPalUrl,
  remainingTime,
} from './payment-rules';
import { TravelerSubscription } from './subscription.service';

describe('remainingTime()', () => {
  const now = new Date('2026-09-21T10:00:00Z');
  const at = (seconds: number) => new Date(now.getTime() + seconds * 1000).toISOString();

  it('counts down in the most readable unit', () => {
    expect(remainingTime(at(35), now).label).toBe('35 s');
    expect(remainingTime(at(42 * 60 + 10), now).label).toBe('42 min 10 s');
    expect(remainingTime(at(3 * 3600 + 5 * 60), now).label).toBe('3 h 05 min');
    expect(remainingTime(at(72 * 3600), now).label).toBe('3 j 00 h');
  });

  it('is expired at and after the deadline', () => {
    expect(remainingTime(at(0), now)).toEqual({ expired: true, totalSeconds: 0, label: 'expiré' });
    expect(remainingTime(at(-60), now).expired).toBe(true);
  });

  it('is not expired, with no label, when the deadline is missing or unparseable', () => {
    expect(remainingTime(null, now).expired).toBe(false);
    expect(remainingTime(undefined, now).label).toBe('');
    expect(remainingTime('garbage', now).expired).toBe(false);
  });
});

describe('extractPayPalOrderId()', () => {
  it('reads the order id from the token of the approval URL', () => {
    expect(extractPayPalOrderId('https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T')).toBe(
      '5O190127TN364715T',
    );
  });

  it('is null for a missing, malformed or token-less URL', () => {
    expect(extractPayPalOrderId(null)).toBeNull();
    expect(extractPayPalOrderId('not a url')).toBeNull();
    expect(extractPayPalOrderId('https://www.paypal.com/checkoutnow')).toBeNull();
  });
});

describe('isTrustedPayPalUrl()', () => {
  it('accepts https on paypal.com and its subdomains', () => {
    expect(isTrustedPayPalUrl('https://www.paypal.com/checkoutnow?token=A')).toBe(true);
    expect(isTrustedPayPalUrl('https://www.sandbox.paypal.com/checkoutnow?token=A')).toBe(true);
    expect(isTrustedPayPalUrl('https://paypal.com/x')).toBe(true);
  });

  it.each([
    'http://www.paypal.com/checkoutnow',
    'https://evil.example.com/checkoutnow?token=A',
    'https://paypal.com.evil.example.com/x',
    'https://notpaypal.com/x',
    'javascript:alert(1)',
    '',
    null,
  ])('refuses %s', (url) => {
    expect(isTrustedPayPalUrl(url as string | null)).toBe(false);
  });
});

describe('currentSubscription()', () => {
  const now = new Date('2026-09-21T10:00:00Z');

  function row(overrides: Partial<TravelerSubscription>): TravelerSubscription {
    return {
      destinationId: 'd1',
      destinationName: 'Lisbon',
      destinationCountry: 'Portugal',
      destinationStartDate: '2026-12-01',
      status: 'CANCELLED',
      subscribedAt: '2026-09-01T10:00:00Z',
      cancelledAt: null,
      expiresAt: null,
      ...overrides,
    };
  }

  it('prefers an ACTIVE row over a pending one', () => {
    const rows = [
      row({ status: 'PENDING_PAYMENT', expiresAt: '2026-09-22T10:00:00Z', subscribedAt: '2026-09-20T10:00:00Z' }),
      row({ status: 'ACTIVE' }),
    ];
    expect(currentSubscription(rows, 'd1', now)?.status).toBe('ACTIVE');
  });

  it('returns a pending row still inside its deadline', () => {
    const pending = row({ status: 'PENDING_PAYMENT', expiresAt: '2026-09-22T10:00:00Z' });
    expect(currentSubscription([pending], 'd1', now)).toBe(pending);
  });

  it('ignores a pending row whose deadline has passed, EXPIRED and CANCELLED rows', () => {
    const rows = [
      row({ status: 'PENDING_PAYMENT', expiresAt: '2026-09-21T09:00:00Z' }),
      row({ status: 'EXPIRED' }),
      row({ status: 'CANCELLED' }),
    ];
    expect(currentSubscription(rows, 'd1', now)).toBeNull();
  });

  it('only looks at the requested travel', () => {
    expect(currentSubscription([row({ status: 'ACTIVE', destinationId: 'other' })], 'd1', now)).toBeNull();
  });
});
