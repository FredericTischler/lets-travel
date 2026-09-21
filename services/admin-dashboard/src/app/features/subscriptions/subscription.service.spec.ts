import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { Subscription, SubscriptionService, TravelerSubscription } from './subscription.service';

describe('SubscriptionService', () => {
  let service: SubscriptionService;
  let httpMock: HttpTestingController;

  const destinationUrl = `${environment.travelApiUrl}/destinations/dest-1/subscriptions`;

  const subscription: Subscription = {
    destinationId: 'dest-1',
    travelerId: 'traveler-1',
    status: 'ACTIVE',
    subscribedAt: '2026-09-01T10:00:00Z',
    cancelledAt: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(SubscriptionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('subscribe() POSTs /destinations/{id}/subscriptions without any body (the traveler is the token subject)', () => {
    let result: Subscription | undefined;
    service.subscribe('dest-1').subscribe((s) => (result = s));

    const req = httpMock.expectOne(destinationUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(subscription);

    expect(result).toEqual(subscription);
  });

  it('subscribe() sends the provider for a paid travel and returns the payment to complete', () => {
    let result: Subscription | undefined;
    service.subscribe('dest-1', { provider: 'PAYPAL' }).subscribe((s) => (result = s));

    const req = httpMock.expectOne(destinationUrl);
    expect(req.request.body).toEqual({ provider: 'PAYPAL' });
    req.flush({
      ...subscription,
      status: 'PENDING_PAYMENT',
      expiresAt: '2026-09-21T11:00:00Z',
      payment: {
        paymentId: 'pay-1',
        provider: 'PAYPAL',
        status: 'PENDING',
        clientSecret: null,
        approveUrl: 'https://www.sandbox.paypal.com/checkoutnow?token=O1',
      },
    });

    expect(result?.status).toBe('PENDING_PAYMENT');
    expect(result?.payment?.approveUrl).toContain('token=O1');
  });

  it('unsubscribe() DELETEs /destinations/{id}/subscriptions', () => {
    let completed = false;
    service.unsubscribe('dest-1').subscribe(() => (completed = true));

    const req = httpMock.expectOne(destinationUrl);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    expect(completed).toBe(true);
  });

  it('mine() GETs /travelers/me/subscriptions', () => {
    const history: TravelerSubscription[] = [
      {
        destinationId: 'dest-1',
        destinationName: 'Lisbon',
        destinationCountry: 'Portugal',
        destinationStartDate: '2026-10-10',
        status: 'ACTIVE',
        subscribedAt: '2026-09-01T10:00:00Z',
        cancelledAt: null,
      },
    ];
    let result: TravelerSubscription[] | undefined;
    service.mine().subscribe((rows) => (result = rows));

    const req = httpMock.expectOne(`${environment.travelApiUrl}/travelers/me/subscriptions`);
    expect(req.request.method).toBe('GET');
    req.flush(history);

    expect(result).toEqual(history);
  });

  it('listForDestination() GETs /destinations/{id}/subscriptions', () => {
    let result: Subscription[] | undefined;
    service.listForDestination('dest-1').subscribe((rows) => (result = rows));

    const req = httpMock.expectOne(destinationUrl);
    expect(req.request.method).toBe('GET');
    req.flush([subscription]);

    expect(result).toEqual([subscription]);
  });

  it('forceUnsubscribe() DELETEs /destinations/{id}/subscriptions/{travelerId}', () => {
    let completed = false;
    service.forceUnsubscribe('dest-1', 'traveler-1').subscribe(() => (completed = true));

    const req = httpMock.expectOne(`${destinationUrl}/traveler-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    expect(completed).toBe(true);
  });
});
