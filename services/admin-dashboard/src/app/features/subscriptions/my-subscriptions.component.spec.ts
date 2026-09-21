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

  afterEach(() => httpMock.verify());

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
});
