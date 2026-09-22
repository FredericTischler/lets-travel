import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { Payment } from '../payments/payment.service';
import { StripeService } from '../payments/stripe.service';
import { PendingPaymentComponent, captureErrorMessage } from './pending-payment.component';
import { PendingPaymentStore } from './pending-payment.store';
import { PaymentCheckout } from './subscription.service';
import { HttpErrorResponse } from '@angular/common/http';

describe('PendingPaymentComponent', () => {
  let fixture: ComponentFixture<PendingPaymentComponent>;
  let component: PendingPaymentComponent;
  let httpMock: HttpTestingController;
  let store: PendingPaymentStore;

  const paymentsUrl = `${environment.paymentApiUrl}/payments`;
  const approveUrl = 'https://www.sandbox.paypal.com/checkoutnow?token=ORDER-1';

  function payment(overrides: Partial<Payment> = {}): Payment {
    return {
      id: 'pay-1',
      amount: 499,
      currency: 'EUR',
      status: 'PENDING',
      externalReference: 'ORDER-1',
      createdAt: '2026-09-21T10:00:00Z',
      provider: 'PAYPAL',
      ...overrides,
    };
  }

  function checkout(overrides: Partial<PaymentCheckout> = {}): PaymentCheckout {
    return {
      paymentId: 'pay-1',
      provider: 'PAYPAL',
      status: 'PENDING',
      clientSecret: null,
      approveUrl,
      ...overrides,
    };
  }

  /** Creates the panel; `loaded` is what GET /payments/{id} answers (null = not called). */
  function setup(
    inputs: { checkout?: PaymentCheckout | null; paymentId?: string | null; expiresAt?: string | null } = {},
    loaded: Payment | 'error' | null = payment(),
  ) {
    fixture = TestBed.createComponent(PendingPaymentComponent);
    component = fixture.componentInstance;
    const expiresAt = inputs.expiresAt === undefined ? new Date(Date.now() + 3600_000).toISOString() : inputs.expiresAt;
    fixture.componentRef.setInput('paymentId', inputs.paymentId === undefined ? 'pay-1' : inputs.paymentId);
    fixture.componentRef.setInput('expiresAt', expiresAt);
    fixture.componentRef.setInput('amount', 499);
    fixture.componentRef.setInput('currency', 'EUR');
    fixture.componentRef.setInput('checkout', inputs.checkout ?? null);
    fixture.detectChanges();
    if (loaded === 'error') {
      httpMock.expectOne(`${paymentsUrl}/pay-1`).flush('x', { status: 500, statusText: 'Error' });
    } else if (loaded) {
      httpMock.expectOne(`${paymentsUrl}/pay-1`).flush(loaded);
    }
    fixture.detectChanges();
  }

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  function button(label: string): HTMLButtonElement | undefined {
    return Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
      (b) => b.textContent?.trim() === label,
    );
  }

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [PendingPaymentComponent, HttpClientTestingModule],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
    store = TestBed.inject(PendingPaymentStore);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('says "en attente de paiement" with the amount and a countdown', () => {
    setup({ checkout: checkout() });

    expect(text()).toContain('En attente de paiement');
    expect(fixture.nativeElement.querySelector('[data-testid="amount"]').textContent).toContain('499');
    expect(fixture.nativeElement.querySelector('[data-testid="countdown"]').textContent).toContain('Votre place est réservée encore');
  });

  it('explains that a MANUAL payment is confirmed by an administrator', () => {
    setup({ checkout: checkout({ provider: 'MANUAL', approveUrl: null }) }, payment({ provider: 'MANUAL' }));

    expect(fixture.nativeElement.querySelector('[data-testid="manual-explanation"]').textContent).toContain(
      'administrateur doit confirmer',
    );
    expect(button('Payer avec PayPal')).toBeUndefined();
  });

  it('is honest about Stripe: reference and status shown, no card form, no client secret displayed', () => {
    setup(
      { checkout: checkout({ provider: 'STRIPE', approveUrl: null, clientSecret: 'pi_secret_123' }) },
      payment({ provider: 'STRIPE', externalReference: 'pi_123' }),
    );

    expect(fixture.nativeElement.querySelector('[data-testid="stripe-explanation"]').textContent).toContain(
      "n'intègre",
    );
    expect(text()).toContain('pi_123');
    expect(text()).not.toContain('pi_secret_123');
    expect(localStorage.getItem('admin-dashboard.pending-payments') ?? '').not.toContain('pi_secret_123');
  });

  describe('with environment.stripePublishableKey configured', () => {
    /** Never resolves: these tests only check which panel is shown, not the full Stripe.js flow (see stripe-card-form.component.spec.ts). */
    const neverResolves = { isConfigured: () => true, getStripe: () => new Promise<null>(() => undefined) };

    async function setupConfigured(
      inputs: { checkout?: PaymentCheckout | null; paymentId?: string | null } = {},
      loaded: Payment | null = payment({ provider: 'STRIPE' }),
    ): Promise<void> {
      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [PendingPaymentComponent, HttpClientTestingModule],
        providers: [{ provide: StripeService, useValue: neverResolves }],
      }).compileComponents();
      httpMock = TestBed.inject(HttpTestingController);
      store = TestBed.inject(PendingPaymentStore);
      setup(inputs, loaded);
    }

    it('shows the Stripe Elements card form instead of the "not integrated" explanation', async () => {
      await setupConfigured({
        checkout: checkout({ provider: 'STRIPE', approveUrl: null, clientSecret: 'pi_secret_123' }),
      });

      expect(fixture.nativeElement.querySelector('[data-testid="stripe-card-form"]')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('[data-testid="stripe-explanation"]')).toBeNull();
    });

    it('explains the card form is unavailable when no fresh client secret is known (e.g. after a reload)', async () => {
      await setupConfigured({ checkout: null });

      expect(fixture.nativeElement.querySelector('[data-testid="stripe-secret-missing"]')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('[data-testid="stripe-card-form"]')).toBeNull();
    });
  });

  it('remembers the PayPal approval URL of a fresh checkout for the way back', () => {
    setup({ checkout: checkout() });

    expect(store.get('pay-1')).toEqual({ provider: 'PAYPAL', approveUrl, orderId: 'ORDER-1' });
  });

  it('redirects to the PayPal approval page, only when the URL is a paypal.com one', () => {
    setup({ checkout: checkout() });
    const navigate = vi.spyOn(component as unknown as { navigate: (url: string) => void }, 'navigate').mockImplementation(() => undefined);

    button('Payer avec PayPal')!.click();
    expect(navigate).toHaveBeenCalledWith(approveUrl);
  });

  it('refuses to redirect to an untrusted approval URL', () => {
    setup({ checkout: checkout({ approveUrl: 'https://evil.example.com/checkoutnow?token=X' }) });
    const navigate = vi.spyOn(component as unknown as { navigate: (url: string) => void }, 'navigate').mockImplementation(() => undefined);

    component['payWithPayPal']();
    fixture.detectChanges();

    expect(navigate).not.toHaveBeenCalled();
    expect(text()).toContain('non fiable');
  });

  it('captures the approved order and reports the payment as settled', () => {
    setup({ checkout: checkout() });
    let settled = false;
    component.settled.subscribe(() => (settled = true));

    button("J'ai approuvé le paiement")!.click();
    const req = httpMock.expectOne(`${paymentsUrl}/paypal/ORDER-1/capture`);
    expect(req.request.method).toBe('POST');
    req.flush(payment({ status: 'COMPLETED' }));
    fixture.detectChanges();

    expect(settled).toBe(true);
    expect(text()).toContain('Paiement reçu');
    expect(store.get('pay-1')).toBeNull();
  });

  it('uses the order id read back from GET /payments/{id} when the approval URL is no longer known', () => {
    setup({ checkout: null }, payment({ externalReference: 'ORDER-9' }));

    expect(fixture.nativeElement.querySelector('[data-testid="no-approve-url"]')).not.toBeNull();
    expect(button('Payer avec PayPal')!.disabled).toBe(true);

    button("J'ai approuvé le paiement")!.click();
    httpMock.expectOne(`${paymentsUrl}/paypal/ORDER-9/capture`).flush(payment({ status: 'COMPLETED' }));
  });

  it('turns a refused capture into an explicit message', () => {
    setup({ checkout: checkout() });

    button("J'ai approuvé le paiement")!.click();
    httpMock
      .expectOne(`${paymentsUrl}/paypal/ORDER-1/capture`)
      .flush({ error: 'x' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(text()).toContain('déjà été traité');
  });

  it('emits cancelRequested from the cancel button (the parent does the DELETE)', () => {
    setup({ checkout: checkout() });
    let cancelled = false;
    component.cancelRequested.subscribe(() => (cancelled = true));

    button('Annuler la réservation')!.click();

    expect(cancelled).toBe(true);
  });

  it('shows an expired reservation as such, without payment actions', () => {
    setup({ checkout: checkout(), expiresAt: new Date(Date.now() - 1000).toISOString() });

    expect(fixture.nativeElement.querySelector('[data-testid="expired-note"]')).not.toBeNull();
    expect(text()).toContain('Expirée');
    expect(button('Payer avec PayPal')).toBeUndefined();
    expect(button('Annuler la réservation')).toBeUndefined();
  });

  it('reports a payment found COMPLETED on refresh as settled', () => {
    setup({ checkout: checkout({ provider: 'STRIPE', approveUrl: null }) }, payment({ provider: 'STRIPE' }));
    let settled = false;
    component.settled.subscribe(() => (settled = true));

    button("Actualiser l'état du paiement")!.click();
    httpMock.expectOne(`${paymentsUrl}/pay-1`).flush(payment({ provider: 'STRIPE', status: 'COMPLETED' }));

    expect(settled).toBe(true);
  });

  it('keeps working with what it knows when the payment cannot be read back', () => {
    setup({ checkout: checkout() }, 'error');

    expect(text()).toContain('PayPal');
  });
});

describe('captureErrorMessage()', () => {
  it.each([
    [409, 'déjà été traité'],
    [404, 'introuvable'],
    [502, 'refusé la capture'],
  ])('explains a %s', (status, expected) => {
    expect(captureErrorMessage(new HttpErrorResponse({ status }))).toContain(expected);
  });

  it('falls back to the backend message, then a generic one', () => {
    expect(captureErrorMessage(new HttpErrorResponse({ status: 400, error: { error: 'boom' } }))).toBe('boom');
    expect(captureErrorMessage(new HttpErrorResponse({ status: 500 }))).toBe(
      'Impossible de finaliser le paiement.',
    );
  });
});
