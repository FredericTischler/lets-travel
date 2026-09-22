import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { StripeService } from './stripe.service';

describe('StripeService', () => {
  let service: StripeService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [StripeService] });
    service = TestBed.inject(StripeService);
  });

  it('is not configured with the default empty publishable key (no real Stripe key in this project)', () => {
    expect(environment.stripePublishableKey).toBe('');
    expect(service.isConfigured()).toBe(false);
  });

  it('resolves to null without loading Stripe.js when unconfigured', async () => {
    const stripe = await service.getStripe();

    expect(stripe).toBeNull();
  });

  it('resolves to null on every call when unconfigured', async () => {
    const [first, second] = await Promise.all([service.getStripe(), service.getStripe()]);

    expect(first).toBeNull();
    expect(second).toBeNull();
  });
});
