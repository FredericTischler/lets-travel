import { TestBed } from '@angular/core/testing';

import { PendingPaymentStore } from './pending-payment.store';

describe('PendingPaymentStore', () => {
  let store: PendingPaymentStore;

  beforeEach(() => {
    localStorage.clear();
    store = TestBed.inject(PendingPaymentStore);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('remembers, returns and forgets a payment', () => {
    const payment = { provider: 'PAYPAL' as const, approveUrl: 'https://www.paypal.com/x?token=A', orderId: 'A' };

    store.remember('p1', payment);
    expect(store.get('p1')).toEqual(payment);
    expect(store.get('unknown')).toBeNull();

    store.forget('p1');
    expect(store.get('p1')).toBeNull();
  });

  it('never persists a Stripe client secret (only provider, approval URL and order id are storable)', () => {
    store.remember('p1', { provider: 'STRIPE', approveUrl: null, orderId: null });

    expect(localStorage.getItem('admin-dashboard.pending-payments')).not.toContain('secret');
  });

  it('survives corrupted storage', () => {
    localStorage.setItem('admin-dashboard.pending-payments', '{not json');

    expect(store.get('p1')).toBeNull();
    expect(() => store.remember('p1', { provider: 'MANUAL', approveUrl: null, orderId: null })).not.toThrow();
  });

  it('survives storage that throws (blocked, quota)', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    expect(store.get('p1')).toBeNull();
    expect(() => store.remember('p1', { provider: 'MANUAL', approveUrl: null, orderId: null })).not.toThrow();
  });
});
