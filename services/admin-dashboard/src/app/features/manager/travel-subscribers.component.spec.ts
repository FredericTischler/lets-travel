import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { Destination } from '../destinations/destination.service';
import { Subscription } from '../subscriptions/subscription.service';
import { TravelSubscribersComponent } from './travel-subscribers.component';

describe('TravelSubscribersComponent', () => {
  let fixture: ComponentFixture<TravelSubscribersComponent>;
  let component: TravelSubscribersComponent;
  let httpMock: HttpTestingController;
  const role = { value: 'TRAVEL_MANAGER' };

  const travelUrl = `${environment.travelApiUrl}/destinations/dest-1`;
  const subscribersUrl = `${travelUrl}/subscriptions`;

  const travel: Destination = {
    id: 'dest-1',
    name: 'Lisbon',
    country: 'Portugal',
    startDate: '2027-01-10',
    endDate: '2027-01-20',
    durationDays: 11,
    managerId: 'me',
    price: 900,
    capacity: 5,
    activities: [],
    accommodations: [],
    createdAt: '2026-01-01T00:00:00Z',
  };

  const active: Subscription = {
    destinationId: 'dest-1',
    travelerId: 'traveler-active',
    status: 'ACTIVE',
    subscribedAt: '2026-09-02T10:00:00Z',
    cancelledAt: null,
  };
  const cancelled: Subscription = {
    destinationId: 'dest-1',
    travelerId: 'traveler-gone',
    status: 'CANCELLED',
    subscribedAt: '2026-09-03T10:00:00Z',
    cancelledAt: '2026-09-04T10:00:00Z',
  };

  beforeEach(async () => {
    role.value = 'TRAVEL_MANAGER';
    await TestBed.configureTestingModule({
      imports: [TravelSubscribersComponent, HttpClientTestingModule],
      providers: [provideRouter([]), { provide: AuthService, useValue: { role: () => role.value } }],
    }).compileComponents();

    fixture = TestBed.createComponent(TravelSubscribersComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('id', 'dest-1');
    httpMock = TestBed.inject(HttpTestingController);
    vi.stubGlobal('confirm', vi.fn(() => true));
  });

  afterEach(() => {
    httpMock.verify();
    vi.unstubAllGlobals();
  });

  function load(subscribers: Subscription[] = [cancelled, active]) {
    fixture.detectChanges();
    httpMock.expectOne(travelUrl).flush(travel);
    httpMock.expectOne(subscribersUrl).flush(subscribers);
    fixture.detectChanges();
  }

  function travelerCells(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('tbody tr.table-row td:first-child') as NodeListOf<HTMLElement>).map(
      (td) => td.textContent?.trim() ?? '',
    );
  }

  it('lists subscribers, active ones first, with the fill rate against capacity', () => {
    load();

    expect(travelerCells()).toEqual(['traveler-active', 'traveler-gone']);
    expect(fixture.nativeElement.querySelector('[data-testid="active-count"]').textContent).toContain(
      '1 abonné(s) actif(s) sur 5 places',
    );
    expect(fixture.nativeElement.textContent).toContain('Lisbon');
  });

  it('offers the force-unsubscribe only for active subscribers', () => {
    load();

    const buttons = fixture.nativeElement.querySelectorAll('tbody button') as NodeListOf<HTMLButtonElement>;
    expect(buttons).toHaveLength(1);
    expect(buttons[0].textContent?.trim()).toBe('Désinscrire');
  });

  it('force-unsubscribes a traveler then refreshes the list', () => {
    load();

    component['forceUnsubscribe'](active);
    const req = httpMock.expectOne(`${subscribersUrl}/traveler-active`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    httpMock.expectOne(subscribersUrl).flush([
      { ...active, status: 'CANCELLED', cancelledAt: '2026-09-05T10:00:00Z' },
      cancelled,
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('tbody button')).toHaveLength(0);
    expect(fixture.nativeElement.querySelector('[data-testid="active-count"]').textContent).toContain('0 abonné');
  });

  it('does nothing when the confirmation is declined', () => {
    vi.stubGlobal('confirm', vi.fn(() => false));
    load();

    component['forceUnsubscribe'](active);

    httpMock.expectNone(`${subscribersUrl}/traveler-active`);
  });

  it('shows the error and refreshes when the force-unsubscribe fails', () => {
    load();

    component['forceUnsubscribe'](active);
    httpMock
      .expectOne(`${subscribersUrl}/traveler-active`)
      .flush({ error: 'No active subscription' }, { status: 404, statusText: 'Not Found' });
    httpMock.expectOne(subscribersUrl).flush([cancelled]);
    fixture.detectChanges();

    expect(component['actionError']()).toBe('No active subscription');
  });

  it("explains a 403 (not the organiser of this travel)", () => {
    fixture.detectChanges();
    httpMock.expectOne(travelUrl).flush(travel);
    httpMock.expectOne(subscribersUrl).flush({ error: 'forbidden' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      "Vous n'êtes pas l'organisateur de ce voyage.",
    );
  });

  it('sends an admin back to the destinations screen, a manager to their travels', () => {
    expect(component['backLink']()).toBe('/manager/travels');

    role.value = 'ADMIN';
    const adminFixture = TestBed.createComponent(TravelSubscribersComponent);
    expect(adminFixture.componentInstance['backLink']()).toBe('/destinations');
    adminFixture.destroy();
  });
});
