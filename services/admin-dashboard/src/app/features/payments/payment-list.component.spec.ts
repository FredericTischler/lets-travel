import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { Payment } from './payment.service';
import { PaymentListComponent } from './payment-list.component';

describe('PaymentListComponent', () => {
  let fixture: ComponentFixture<PaymentListComponent>;
  let component: PaymentListComponent;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.paymentApiUrl}/payments`;

  const samplePayment: Payment = {
    id: 'pay-1',
    amount: 199.99,
    currency: 'EUR',
    status: 'PENDING',
    externalReference: null,
    createdAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PaymentListComponent, HttpClientTestingModule],
      providers: [{ provide: AuthService, useValue: { getCurrentUserId: () => 'user-1' } }],
    }).compileComponents();

    fixture = TestBed.createComponent(PaymentListComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('loads and renders the payment list on init', () => {
    fixture.detectChanges(); // triggers ngOnInit -> list()

    httpMock.expectOne(baseUrl).flush([samplePayment]);
    fixture.detectChanges();

    expect(component['payments']()).toEqual([samplePayment]);
    expect(component['loading']()).toBe(false);

    const cells = fixture.nativeElement.querySelectorAll('td.table-cell');
    const text = Array.from(cells as NodeListOf<HTMLElement>).map((cell) => cell.textContent?.trim());
    expect(text).toContain('EUR');
    expect(text).toContain('PENDING');
  });

  it('shows an error message when loading the list fails', () => {
    fixture.detectChanges();

    httpMock.expectOne(baseUrl).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(component['error']()).toBe('Impossible de charger la liste des paiements.');
  });

  it('createPayment() submits the form and reloads the list on success', () => {
    fixture.detectChanges();
    httpMock.expectOne(baseUrl).flush([]);
    fixture.detectChanges();

    component['createAmount'] = 50;
    component['createCurrency'] = 'USD';
    component.createPayment();

    const createReq = httpMock.expectOne(baseUrl);
    expect(createReq.request.method).toBe('POST');
    expect(createReq.request.body).toEqual({ userId: 'user-1', amount: 50, currency: 'USD' });
    createReq.flush(samplePayment);

    // createPayment() reloads the list after success.
    httpMock.expectOne(baseUrl).flush([samplePayment]);

    expect(component['createAmount']).toBeNull();
    expect(component['createCurrency']).toBe('');
  });

  it('createPayment() does nothing when the amount is missing', () => {
    fixture.detectChanges();
    httpMock.expectOne(baseUrl).flush([]);

    component['createAmount'] = null;
    component.createPayment();

    httpMock.expectNone(baseUrl);
  });

  it('markCompleted() updates the status and reloads the list', () => {
    fixture.detectChanges();
    httpMock.expectOne(baseUrl).flush([samplePayment]);
    fixture.detectChanges();

    component.markCompleted(samplePayment);

    const updateReq = httpMock.expectOne(`${baseUrl}/pay-1/status`);
    expect(updateReq.request.method).toBe('PATCH');
    expect(updateReq.request.body).toEqual({ status: 'COMPLETED' });
    updateReq.flush({ ...samplePayment, status: 'COMPLETED' });

    httpMock.expectOne(baseUrl).flush([{ ...samplePayment, status: 'COMPLETED' }]);

    expect(component['updatingPaymentId']()).toBeNull();
  });
});
