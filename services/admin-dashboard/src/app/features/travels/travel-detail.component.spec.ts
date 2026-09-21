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
});
