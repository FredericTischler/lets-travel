import { ComponentFixture, TestBed } from '@angular/core/testing';

import { StripeCardFormComponent } from './stripe-card-form.component';
import { StripeService } from './stripe.service';

/** Flushes the microtask queue (enough for a couple of chained `.then()` calls). */
async function flushMicrotasks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('StripeCardFormComponent', () => {
  let fixture: ComponentFixture<StripeCardFormComponent>;
  let component: StripeCardFormComponent;
  let confirmCardPayment: ReturnType<typeof vi.fn>;
  let cardMount: ReturnType<typeof vi.fn>;
  let cardDestroy: ReturnType<typeof vi.fn>;
  let fakeCard: { mount: typeof cardMount; on: (event: string, handler: (e: unknown) => void) => void; destroy: typeof cardDestroy };
  let handlers: Record<string, (event: unknown) => void>;

  const el = () => fixture.nativeElement as HTMLElement;
  const button = () => el().querySelector('button') as HTMLButtonElement;

  /** Renders the form with a Stripe.js stub that resolves after `flushMicrotasks()`. */
  async function setup(): Promise<void> {
    handlers = {};
    cardMount = vi.fn();
    cardDestroy = vi.fn();
    fakeCard = {
      mount: cardMount,
      on: (event, handler) => {
        handlers[event] = handler;
      },
      destroy: cardDestroy,
    };
    confirmCardPayment = vi.fn();
    const fakeStripe = {
      elements: vi.fn(() => ({ create: vi.fn(() => fakeCard) })),
      confirmCardPayment,
    };
    const stripeServiceStub: Pick<StripeService, 'isConfigured' | 'getStripe'> = {
      isConfigured: () => true,
      getStripe: () => Promise.resolve(fakeStripe as never),
    };

    await TestBed.configureTestingModule({
      imports: [StripeCardFormComponent],
      providers: [{ provide: StripeService, useValue: stripeServiceStub }],
    }).compileComponents();

    fixture = TestBed.createComponent(StripeCardFormComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('clientSecret', 'pi_123_secret_abc');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function makeReady(): void {
    handlers['ready']?.({ elementType: 'card' });
    fixture.detectChanges();
  }

  it('mounts the card element into its container as soon as Stripe.js resolves', async () => {
    await setup();

    expect(cardMount).toHaveBeenCalledWith(el().querySelector('[data-testid="stripe-card-element"]'));
  });

  it('disables the pay button until the card element reports ready', async () => {
    await setup();
    expect(button().disabled).toBe(true);

    makeReady();

    expect(button().disabled).toBe(false);
    expect(button().textContent?.trim()).toBe('Payer par carte');
  });

  it('confirms the PaymentIntent with the client secret and the mounted card, and emits paid on success', async () => {
    await setup();
    makeReady();
    confirmCardPayment.mockResolvedValue({ paymentIntent: { status: 'succeeded' } });
    let paid = false;
    component.paid.subscribe(() => (paid = true));

    button().click();

    expect(confirmCardPayment).toHaveBeenCalledWith('pi_123_secret_abc', {
      payment_method: { card: fakeCard },
    });

    await flushMicrotasks();
    fixture.detectChanges();

    expect(paid).toBe(true);
  });

  it('shows the Stripe error message as-is on a refused card, without emitting paid', async () => {
    await setup();
    makeReady();
    confirmCardPayment.mockResolvedValue({ error: { message: 'Your card was declined.' } });
    let paid = false;
    component.paid.subscribe(() => (paid = true));

    button().click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(el().textContent).toContain('Your card was declined.');
    expect(paid).toBe(false);
  });

  it('shows a card validation error reported by the change event', async () => {
    await setup();
    makeReady();

    handlers['change']?.({ elementType: 'card', error: { message: 'Your card number is incomplete.' } });
    fixture.detectChanges();

    expect(el().textContent).toContain('Your card number is incomplete.');
  });

  it('shows an error, never a broken form, when Stripe.js itself fails to load', async () => {
    // getStripe() resolving null (unconfigured, or a real load failure) must never crash the form.
    await TestBed.configureTestingModule({
      imports: [StripeCardFormComponent],
      providers: [
        {
          provide: StripeService,
          useValue: { isConfigured: () => true, getStripe: () => Promise.resolve(null) } satisfies Pick<
            StripeService,
            'isConfigured' | 'getStripe'
          >,
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(StripeCardFormComponent);
    fixture.componentRef.setInput('clientSecret', 'pi_x');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(el().textContent).toContain("n'a pas pu être chargé");
    expect(el().querySelector('button')).toBeNull();
  });

  it('destroys the mounted card element when the component is destroyed', async () => {
    await setup();

    fixture.destroy();

    expect(cardDestroy).toHaveBeenCalled();
  });
});
