import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { Payment, PaymentService } from './payment.service';

describe('PaymentService', () => {
  let service: PaymentService;
  let httpMock: HttpTestingController;
  let authServiceStub: { getCurrentUserId: () => string | null };

  const baseUrl = `${environment.paymentApiUrl}/payments`;

  const samplePayment: Payment = {
    id: 'pay-1',
    amount: 199.99,
    currency: 'EUR',
    status: 'PENDING',
    externalReference: null,
    createdAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(() => {
    authServiceStub = { getCurrentUserId: () => 'user-1' };

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [PaymentService, { provide: AuthService, useValue: authServiceStub }],
    });

    service = TestBed.inject(PaymentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('list() performs a GET /payments and returns the response', () => {
    let result: Payment[] | undefined;

    service.list().subscribe((payments) => (result = payments));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('GET');
    req.flush([samplePayment]);

    expect(result).toEqual([samplePayment]);
  });

  it('create() performs a POST /payments with the current user id, amount and currency', () => {
    let result: Payment | undefined;

    service.create(199.99, 'EUR').subscribe((payment) => (result = payment));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ userId: 'user-1', amount: 199.99, currency: 'EUR' });
    req.flush(samplePayment);

    expect(result).toEqual(samplePayment);
  });

  it('create() errors without calling the API when there is no logged-in user', () => {
    authServiceStub.getCurrentUserId = () => null;

    let error: Error | undefined;
    service.create(199.99, 'EUR').subscribe({ error: (err) => (error = err) });

    httpMock.expectNone(baseUrl);
    expect(error?.message).toContain('non authentifié');
  });

  it('updateStatus() performs a PATCH /payments/{id}/status with the given status', () => {
    let result: Payment | undefined;

    service.updateStatus('pay-1', 'COMPLETED').subscribe((payment) => (result = payment));

    const req = httpMock.expectOne(`${baseUrl}/pay-1/status`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ status: 'COMPLETED' });
    req.flush({ ...samplePayment, status: 'COMPLETED' });

    expect(result?.status).toBe('COMPLETED');
  });

  it('delete() performs a DELETE /payments/{id}', () => {
    let completed = false;

    service.delete('pay-1').subscribe(() => (completed = true));

    const req = httpMock.expectOne(`${baseUrl}/pay-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    expect(completed).toBe(true);
  });

  it('get() performs a GET /payments/{id}', () => {
    let result: Payment | undefined;

    service.get('pay-1').subscribe((payment) => (result = payment));

    const req = httpMock.expectOne(`${baseUrl}/pay-1`);
    expect(req.request.method).toBe('GET');
    req.flush({ ...samplePayment, provider: 'PAYPAL', externalReference: 'ORDER-1' });

    expect(result?.provider).toBe('PAYPAL');
  });

  it('capturePayPal() POSTs /payments/paypal/{orderId}/capture with the order id encoded', () => {
    let result: Payment | undefined;

    service.capturePayPal('ORDER/1').subscribe((payment) => (result = payment));

    const req = httpMock.expectOne(`${baseUrl}/paypal/ORDER%2F1/capture`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ ...samplePayment, status: 'COMPLETED' });

    expect(result?.status).toBe('COMPLETED');
  });
});
