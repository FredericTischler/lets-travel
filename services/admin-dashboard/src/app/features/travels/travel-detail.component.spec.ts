import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { Destination } from '../destinations/destination.service';
import { TravelerSubscription } from '../subscriptions/subscription.service';
import { TravelDetailComponent } from './travel-detail.component';

/** ISO local date `days` days from now. */
function inDays(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toLocaleDateString('sv-SE');
}

describe('TravelDetailComponent', () => {
  let fixture: ComponentFixture<TravelDetailComponent>;
  let component: TravelDetailComponent;
  let httpMock: HttpTestingController;
  const currentUserId = { value: 'traveler-1' };

  const travelUrl = `${environment.travelApiUrl}/destinations/dest-1`;
  const subscriptionsUrl = `${travelUrl}/subscriptions`;
  const historyUrl = `${environment.travelApiUrl}/travelers/me/subscriptions`;
  const reportsUrl = `${environment.identityApiUrl}/reports`;
  const buddiesUrl = `${travelUrl}/buddies`;

  function travel(startInDays: number, overrides: Partial<Destination> = {}): Destination {
    return {
      id: 'dest-1',
      name: 'Lisbon',
      country: 'Portugal',
      startDate: inDays(startInDays),
      endDate: inDays(startInDays + 5),
      durationDays: 6,
      managerId: 'manager-1',
      price: 800,
      capacity: 10,
      activities: [{ id: 'a1', name: 'Tram 28 ride' }],
      accommodations: [
        { id: 'h1', name: 'Hotel Lisboa', type: 'HOTEL', checkIn: null, checkOut: null },
      ],
      createdAt: '2026-01-01T00:00:00Z',
      ...overrides,
    };
  }

  function history(active: boolean): TravelerSubscription[] {
    return active
      ? [
          {
            destinationId: 'dest-1',
            destinationName: 'Lisbon',
            destinationCountry: 'Portugal',
            destinationStartDate: '2027-01-01',
            status: 'ACTIVE',
            subscribedAt: '2026-09-01T10:00:00Z',
            cancelledAt: null,
          },
        ]
      : [];
  }

  beforeEach(async () => {
    currentUserId.value = 'traveler-1';
    await TestBed.configureTestingModule({
      imports: [TravelDetailComponent, HttpClientTestingModule],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { getCurrentUserId: () => currentUserId.value } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TravelDetailComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('id', 'dest-1');
    httpMock = TestBed.inject(HttpTestingController);
    vi.stubGlobal('confirm', vi.fn(() => true));
  });

  afterEach(() => {
    httpMock.verify();
    vi.unstubAllGlobals();
  });

  function load(t: Destination, subscribed = false, reportCount = 2) {
    fixture.detectChanges();
    httpMock.expectOne(travelUrl).flush(t);
    httpMock.expectOne(historyUrl).flush(history(subscribed));
    if (t.managerId) {
      httpMock.expectOne(`${reportsUrl}/count/${t.managerId}`).flush({ count: reportCount });
    }
    if (subscribed) {
      httpMock.expectOne(buddiesUrl).flush([]);
    }
    fixture.detectChanges();
  }

  function button(label: string): HTMLButtonElement | undefined {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>);
    return buttons.find((b) => b.textContent?.trim() === label);
  }

  function alerts(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('[role="alert"]') as NodeListOf<HTMLElement>).map(
      (el) => el.textContent?.trim() ?? '',
    );
  }

  it('renders the travel details, activities, accommodations and the organiser report count', () => {
    load(travel(30));

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Lisbon');
    expect(text).toContain('800.00 €');
    expect(text).toContain('Tram 28 ride');
    expect(text).toContain('Hotel Lisboa');
    expect(fixture.nativeElement.querySelector('[data-testid="report-count"]').textContent).toContain('2');
  });

  it('shows a clear message when the travel does not exist', () => {
    fixture.detectChanges();
    httpMock.expectOne(travelUrl).flush({ error: 'nope' }, { status: 404, statusText: 'Not Found' });
    httpMock.expectOne(historyUrl).flush([]);
    fixture.detectChanges();

    expect(alerts()).toEqual(['Ce voyage est introuvable.']);
  });

  describe('subscribing', () => {
    it('subscribes and then offers to unsubscribe', () => {
      load(travel(30));

      button("S'inscrire")!.click();
      const req = httpMock.expectOne(subscriptionsUrl);
      expect(req.request.method).toBe('POST');
      req.flush({ destinationId: 'dest-1', travelerId: 'traveler-1', status: 'ACTIVE', subscribedAt: 'x', cancelledAt: null });
      fixture.detectChanges();

      expect(component['subscribed']()).toBe(true);
      expect(button('Se désinscrire')).toBeDefined();
      expect(fixture.nativeElement.textContent).toContain('Vous êtes inscrit à ce voyage.');
    });

    it('explains a 409 on subscribe', () => {
      load(travel(30));

      button("S'inscrire")!.click();
      httpMock.expectOne(subscriptionsUrl).flush({ error: 'already' }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();

      expect(alerts()[0]).toContain('Inscription impossible');
      expect(component['subscribed']()).toBe(false);
    });

    it('disables subscribing to a travel that already started', () => {
      load(travel(-2));

      expect(button("S'inscrire")!.disabled).toBe(true);
      expect(fixture.nativeElement.textContent).toContain("l'inscription n'est plus possible");
    });
  });

  describe('unsubscribing', () => {
    it('shows the deadline while the cutoff is not reached', () => {
      load(travel(30), true);

      expect(button('Se désinscrire')).toBeDefined();
      expect(fixture.nativeElement.textContent).toContain('Vous pouvez annuler jusqu');
    });

    it('unsubscribes and offers to subscribe again', () => {
      load(travel(30), true);

      button('Se désinscrire')!.click();
      const req = httpMock.expectOne(subscriptionsUrl);
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });
      fixture.detectChanges();

      expect(component['subscribed']()).toBe(false);
      expect(button("S'inscrire")).toBeDefined();
      expect(fixture.nativeElement.textContent).toContain('Votre inscription a été annulée.');
    });

    it('warns up front when the 3-day cutoff is already past', () => {
      load(travel(1), true);

      expect(fixture.nativeElement.textContent).toContain("Le délai d'annulation (3 jours avant le départ) est dépassé");
    });

    it('turns the 409 of the 3-day cutoff into an explicit message and stays subscribed', () => {
      load(travel(1), true);

      button('Se désinscrire')!.click();
      httpMock.expectOne(subscriptionsUrl).flush({ error: 'cutoff' }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();

      const message = alerts()[0];
      expect(message).toContain('Désinscription refusée');
      expect(message).toContain('moins de 3 jours avant le départ');
      expect(component['subscribed']()).toBe(true);
    });

    it('does nothing when the user declines the confirmation', () => {
      vi.stubGlobal('confirm', vi.fn(() => false));
      load(travel(30), true);

      button('Se désinscrire')!.click();

      httpMock.expectNone(subscriptionsUrl);
    });

    it('handles a 404 (no active subscription any more)', () => {
      load(travel(30), true);

      button('Se désinscrire')!.click();
      httpMock.expectOne(subscriptionsUrl).flush({ error: 'none' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();

      expect(component['subscribed']()).toBe(false);
      expect(alerts()[0]).toContain('Aucune inscription active');
    });
  });

  describe('reporting the organiser', () => {
    it('sends the manager id and the trimmed reason, then confirms and refreshes the count', () => {
      load(travel(30));

      button("Signaler l'organisateur")!.click();
      fixture.detectChanges();
      component['reportReason'] = '  Rude behaviour <b>x</b>  ';
      component['submitReport']();

      const req = httpMock.expectOne(reportsUrl);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ reportedUserId: 'manager-1', reason: 'Rude behaviour <b>x</b>' });
      req.flush({ id: 'r1' });
      httpMock.expectOne(`${reportsUrl}/count/manager-1`).flush({ count: 3 });
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('Votre signalement a été transmis');
      expect(component['reportCount']()).toBe(3);
    });

    it('does not send an empty reason', () => {
      load(travel(30));

      component['reportReason'] = '   ';
      component['submitReport']();

      httpMock.expectNone(reportsUrl);
    });

    it('shows the backend error when the report is refused', () => {
      load(travel(30));

      component['reportReason'] = 'x';
      component['submitReport']();
      httpMock.expectOne(reportsUrl).flush({ error: 'User not found' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();

      expect(component['reportError']()).toBe('User not found');
    });

    it('does not offer to report yourself', () => {
      currentUserId.value = 'manager-1';
      load(travel(30));

      expect(button("Signaler l'organisateur")).toBeUndefined();
    });
  });

  describe('paying for a subscription', () => {
    const paymentUrl = `${environment.paymentApiUrl}/payments/pay-1`;
    const feedbackUrl = `${environment.travelApiUrl}/travelers/me/feedback`;

    function loadWith(t: Destination, rows: TravelerSubscription[]) {
      fixture.detectChanges();
      httpMock.expectOne(travelUrl).flush(t);
      httpMock.expectOne(historyUrl).flush(rows);
      if (t.managerId) {
        httpMock.expectOne(`${reportsUrl}/count/${t.managerId}`).flush({ count: 0 });
      }
      if (rows.some((r) => r.status === 'ACTIVE')) {
        httpMock.expectOne(buddiesUrl).flush([]);
      }
      fixture.detectChanges();
    }

    function pendingRow(overrides: Partial<TravelerSubscription> = {}): TravelerSubscription {
      return {
        destinationId: 'dest-1',
        destinationName: 'Lisbon',
        destinationCountry: 'Portugal',
        destinationStartDate: inDays(30),
        status: 'PENDING_PAYMENT',
        subscribedAt: '2026-09-21T10:00:00Z',
        cancelledAt: null,
        paymentId: 'pay-1',
        expiresAt: new Date(Date.now() + 3600_000).toISOString(),
        ...overrides,
      };
    }

    it('asks how to pay for a priced travel, defaulting to PayPal, and explains the MANUAL mode', () => {
      loadWith(travel(30), []);

      expect(fixture.nativeElement.querySelectorAll('[data-testid="provider-choice"] input').length).toBe(3);
      expect(component['provider']()).toBe('PAYPAL');

      component['provider'].set('MANUAL');
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Un administrateur confirmera votre règlement');
    });

    it('does not ask anything for a free travel, and sends no body', () => {
      loadWith(travel(30, { price: 0 }), []);

      expect(fixture.nativeElement.querySelector('[data-testid="provider-choice"]')).toBeNull();
      button("S'inscrire")!.click();
      const req = httpMock.expectOne(subscriptionsUrl);
      expect(req.request.body).toBeNull();
      req.flush({ destinationId: 'dest-1', travelerId: 'traveler-1', status: 'ACTIVE', subscribedAt: 'x', cancelledAt: null });
    });

    it('sends the chosen provider and shows the pending-payment panel with the countdown', () => {
      loadWith(travel(30), []);

      component['provider'].set('PAYPAL');
      button("S'inscrire")!.click();
      const req = httpMock.expectOne(subscriptionsUrl);
      expect(req.request.body).toEqual({ provider: 'PAYPAL' });
      req.flush({
        id: 'sub-1',
        destinationId: 'dest-1',
        travelerId: 'traveler-1',
        status: 'PENDING_PAYMENT',
        subscribedAt: 'x',
        cancelledAt: null,
        expiresAt: new Date(Date.now() + 3600_000).toISOString(),
        paymentId: 'pay-1',
        amount: 800,
        currency: 'EUR',
        payment: {
          paymentId: 'pay-1',
          provider: 'PAYPAL',
          status: 'PENDING',
          clientSecret: null,
          approveUrl: 'https://www.sandbox.paypal.com/checkoutnow?token=O1',
        },
      });
      fixture.detectChanges();
      httpMock.expectOne(paymentUrl).flush({ id: 'pay-1', status: 'PENDING', provider: 'PAYPAL', externalReference: 'O1' });
      fixture.detectChanges();

      expect(component['subscribed']()).toBe(false);
      expect(fixture.nativeElement.querySelector('[data-testid="pending-payment"]')).not.toBeNull();
      expect(fixture.nativeElement.textContent).toContain('En attente de paiement');
      expect(button("S'inscrire")).toBeUndefined();
    });

    it('shows the pending panel again when the history already holds a pending reservation', () => {
      loadWith(travel(30), [pendingRow()]);
      httpMock.expectOne(paymentUrl).flush({ id: 'pay-1', status: 'PENDING', provider: 'MANUAL', externalReference: null });
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('[data-testid="manual-explanation"]')).not.toBeNull();
    });

    it('offers to subscribe again when the pending reservation has expired', () => {
      loadWith(travel(30), [pendingRow({ status: 'EXPIRED' })]);

      expect(fixture.nativeElement.querySelector('[data-testid="pending-payment"]')).toBeNull();
      expect(button("S'inscrire")).toBeDefined();
    });

    it('cancels a pending reservation through DELETE, cutoff or not', () => {
      loadWith(travel(1), [pendingRow()]);
      httpMock.expectOne(paymentUrl).flush({ id: 'pay-1', status: 'PENDING', provider: 'MANUAL', externalReference: null });
      fixture.detectChanges();

      button('Annuler la réservation')!.click();
      const req = httpMock.expectOne(subscriptionsUrl);
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });
      fixture.detectChanges();

      expect(component['pending']()).toBeNull();
      expect(fixture.nativeElement.textContent).toContain('Votre réservation a été annulée.');
    });

    it('turns a 502 of the payment service into a "nothing was reserved" message', () => {
      loadWith(travel(30), []);

      button("S'inscrire")!.click();
      httpMock.expectOne(subscriptionsUrl).flush({ error: 'x' }, { status: 502, statusText: 'Bad Gateway' });
      fixture.detectChanges();

      expect(alerts()[0]).toContain('rien n’a été réservé');
    });

    it('refreshes the history when the panel reports the payment settled', () => {
      loadWith(travel(30), [pendingRow()]);
      httpMock.expectOne(paymentUrl).flush({ id: 'pay-1', status: 'COMPLETED', provider: 'MANUAL', externalReference: null });

      httpMock.expectOne(historyUrl).flush([{ ...pendingRow(), status: 'ACTIVE' }]);
      httpMock.expectOne(buddiesUrl).flush([]);
      fixture.detectChanges();

      expect(component['subscribed']()).toBe(true);
      expect(button('Se désinscrire')).toBeDefined();
    });

    describe('feedback', () => {
      const ended = () => travel(-20);
      const activeRow = () => ({ ...pendingRow({ status: 'ACTIVE', paymentId: null, expiresAt: null }) });
      const myFeedback = {
        id: 'f1',
        travelerId: 'traveler-1',
        destinationId: 'dest-1',
        destinationName: 'Lisbon',
        destinationCountry: 'Portugal',
        destinationEndDate: inDays(-15),
        rating: 4,
        comment: 'Génial <b>vraiment</b>',
        createdAt: '2026-09-01T10:00:00Z',
      };

      function loadEnded(rows: TravelerSubscription[], feedback: unknown[]) {
        fixture.detectChanges();
        httpMock.expectOne(travelUrl).flush(ended());
        httpMock.expectOne(feedbackUrl).flush(feedback);
        httpMock.expectOne(historyUrl).flush(rows);
        httpMock.expectOne(`${reportsUrl}/count/manager-1`).flush({ count: 0 });
        if (rows.some((r) => r.status === 'ACTIVE')) {
          httpMock.expectOne(buddiesUrl).flush([]);
        }
        fixture.detectChanges();
      }

      it('offers the form to an ACTIVE participant of an ended travel who has not rated it', () => {
        loadEnded([activeRow()], []);

        expect(fixture.nativeElement.querySelector('[data-testid="feedback-form"]')).not.toBeNull();
      });

      it('shows the traveler’s own feedback, escaped, instead of the form once given', () => {
        loadEnded([activeRow()], [myFeedback]);

        expect(fixture.nativeElement.querySelector('[data-testid="feedback-form"]')).toBeNull();
        const comment = fixture.nativeElement.querySelector('[data-testid="feedback-comment"]');
        expect(comment.textContent).toContain('Génial <b>vraiment</b>');
        expect(comment.querySelector('b')).toBeNull();
      });

      it('does not offer the form to someone who was not subscribed', () => {
        loadEnded([], []);

        expect(fixture.nativeElement.querySelector('[data-testid="feedback-form"]')).toBeNull();
      });

      it('does not ask for feedback at all while the travel is not over', () => {
        loadWith(travel(30), [activeRow()]);

        httpMock.expectNone(feedbackUrl);
        expect(fixture.nativeElement.querySelector('[data-testid="feedback-form"]')).toBeNull();
      });
    });
  });

  it('links to the public page of the organiser', () => {
    load(travel(30));

    const link = fixture.nativeElement.querySelector('[data-testid="manager-link"]') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/managers/manager-1');
  });
});
