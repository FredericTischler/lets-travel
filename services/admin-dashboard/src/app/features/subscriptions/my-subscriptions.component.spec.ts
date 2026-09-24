import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { MySubscriptionsComponent } from './my-subscriptions.component';
import { TravelerSubscription } from './subscription.service';

function inDays(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toLocaleDateString('sv-SE');
}

describe('MySubscriptionsComponent', () => {
  let fixture: ComponentFixture<MySubscriptionsComponent>;
  let httpMock: HttpTestingController;

  const url = `${environment.travelApiUrl}/travelers/me/subscriptions`;

  function row(overrides: Partial<TravelerSubscription>): TravelerSubscription {
    return {
      destinationId: 'd',
      destinationName: 'Somewhere',
      destinationCountry: 'Nowhere',
      destinationStartDate: inDays(30),
      status: 'ACTIVE',
      subscribedAt: '2026-09-01T10:00:00Z',
      cancelledAt: null,
      ...overrides,
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MySubscriptionsComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(MySubscriptionsComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // The traveler-badges block (its own spec covers it) asks for the caller's badges on init.
    httpMock
      .match(`${environment.travelApiUrl}/travelers/me/badges`)
      .forEach((request) => request.flush({ destinationsVisited: 0, countriesVisited: 0, reviewsGiven: 0, badges: [] }));
    httpMock.verify();
  });

  function count(testId: string): string {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`).textContent.trim();
  }

  it('splits the history into upcoming, past participation and cancellations', () => {
    fixture.detectChanges();
    httpMock.expectOne(url).flush([
      row({ destinationId: 'up', destinationName: 'Upcoming trip' }),
      row({ destinationId: 'past1', destinationName: 'Past trip A', destinationStartDate: inDays(-40) }),
      row({ destinationId: 'past2', destinationName: 'Past trip B', destinationStartDate: inDays(-10) }),
      row({
        destinationId: 'cx',
        destinationName: 'Cancelled trip',
        status: 'CANCELLED',
        cancelledAt: '2026-09-05T10:00:00Z',
      }),
    ]);
    fixture.detectChanges();

    expect(count('count-upcoming')).toBe('1');
    expect(count('count-past')).toBe('2');
    expect(count('count-cancelled')).toBe('1');

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Upcoming trip');
    expect(text).toContain('Past trip A');
    expect(text).toContain('Cancelled trip');
    expect(text).toContain('Annulée');
  });

  it('links each row to the travel detail', () => {
    fixture.detectChanges();
    httpMock.expectOne(url).flush([row({ destinationId: 'up', destinationName: 'Upcoming trip' })]);
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('tbody a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/travels/up');
  });

  it('shows empty states when there is no history', () => {
    fixture.detectChanges();
    httpMock.expectOne(url).flush([]);
    fixture.detectChanges();

    expect(count('count-upcoming')).toBe('0');
    expect(fixture.nativeElement.textContent).toContain('Aucun voyage.');
  });

  it('shows an error when the history cannot be loaded', () => {
    fixture.detectChanges();
    httpMock.expectOne(url).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Impossible de charger vos abonnements.',
    );
  });

  describe('payment states', () => {
    const paymentUrl = `${environment.paymentApiUrl}/payments/pay-1`;
    const inOneHour = () => new Date(Date.now() + 3600_000).toISOString();

    it('shows a pending reservation with its payment panel, outside the three counters', () => {
      fixture.detectChanges();
      httpMock.expectOne(url).flush([
        row({
          destinationId: 'p',
          destinationName: 'Pending trip',
          status: 'PENDING_PAYMENT',
          paymentId: 'pay-1',
          expiresAt: inOneHour(),
        }),
      ]);
      fixture.detectChanges();
      httpMock
        .expectOne(paymentUrl)
        .flush({ id: 'pay-1', status: 'PENDING', provider: 'MANUAL', externalReference: null });
      fixture.detectChanges();

      const list = fixture.nativeElement.querySelector('[data-testid="pending-list"]');
      expect(list.textContent).toContain('Pending trip');
      expect(list.textContent).toContain('En attente de paiement');
      expect(list.querySelector('[data-testid="manual-explanation"]')).not.toBeNull();
      expect(count('count-upcoming')).toBe('0');
    });

    it('lists EXPIRED reservations, and unpaid ones past their deadline, as expired', () => {
      fixture.detectChanges();
      httpMock.expectOne(url).flush([
        row({ destinationId: 'e1', destinationName: 'Expired one', status: 'EXPIRED' }),
        row({
          destinationId: 'e2',
          destinationName: 'Late one',
          status: 'PENDING_PAYMENT',
          expiresAt: new Date(Date.now() - 60_000).toISOString(),
        }),
      ]);
      fixture.detectChanges();

      const text = fixture.nativeElement.textContent;
      expect(text).toContain('Réservations expirées');
      expect(text).toContain('Expired one');
      expect(text).toContain('Late one');
      expect(fixture.nativeElement.querySelector('[data-testid="pending-list"]')).toBeNull();
    });

    it('cancels a pending reservation and reloads the history', () => {
      vi.stubGlobal('confirm', vi.fn(() => true));
      fixture.detectChanges();
      httpMock.expectOne(url).flush([
        row({
          destinationId: 'p',
          destinationName: 'Pending trip',
          status: 'PENDING_PAYMENT',
          paymentId: 'pay-1',
          expiresAt: inOneHour(),
        }),
      ]);
      fixture.detectChanges();
      httpMock.expectOne(paymentUrl).flush({ id: 'pay-1', status: 'PENDING', provider: 'MANUAL', externalReference: null });
      fixture.detectChanges();

      (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
        .find((b) => b.textContent?.trim() === 'Annuler la réservation')!
        .click();
      const del = httpMock.expectOne(`${environment.travelApiUrl}/destinations/p/subscriptions`);
      expect(del.request.method).toBe('DELETE');
      del.flush(null, { status: 204, statusText: 'No Content' });
      httpMock.expectOne(url).flush([]);
      vi.unstubAllGlobals();
    });

    it('links each past trip to its feedback', () => {
      fixture.detectChanges();
      httpMock.expectOne(url).flush([
        row({ destinationId: 'past1', destinationName: 'Past trip A', destinationStartDate: inDays(-40) }),
      ]);
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('Donner / voir mon avis');
    });
  });
});
