import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { PaypalReturnComponent } from './paypal-return.component';

describe('PaypalReturnComponent', () => {
  let fixture: ComponentFixture<PaypalReturnComponent>;
  let httpMock: HttpTestingController;

  const captureUrl = (order: string) => `${environment.paymentApiUrl}/payments/paypal/${order}/capture`;

  function setup(token: string | undefined) {
    fixture = TestBed.createComponent(PaypalReturnComponent);
    fixture.componentRef.setInput('token', token);
    fixture.detectChanges();
  }

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PaypalReturnComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('captures the order named by the token and confirms', () => {
    setup('ORDER-1');
    expect(text()).toContain('Finalisation de votre paiement');

    const req = httpMock.expectOne(captureUrl('ORDER-1'));
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'pay-1', status: 'COMPLETED' });
    fixture.detectChanges();

    expect(text()).toContain('Paiement reçu');
    expect(fixture.nativeElement.querySelector('a').getAttribute('href')).toBe('/my-subscriptions');
  });

  it('does not call the backend without a token', () => {
    setup(undefined);

    httpMock.expectNone(() => true);
    expect(text()).toContain('Retour PayPal invalide');
  });

  it('shows the reason when the capture is refused', () => {
    setup('ORDER-1');

    httpMock.expectOne(captureUrl('ORDER-1')).flush({ error: 'x' }, { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    expect(text()).toContain('refusé la capture');
  });

  it('does not claim success when PayPal did not complete the payment', () => {
    setup('ORDER-1');

    httpMock.expectOne(captureUrl('ORDER-1')).flush({ id: 'pay-1', status: 'FAILED' });
    fixture.detectChanges();

    expect(text()).toContain('n’a pas confirmé');
    expect(text()).not.toContain('Paiement reçu');
  });
});
