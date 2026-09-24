import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { ItinerarySuggestionsComponent } from './itinerary-suggestions.component';
import { ItinerarySuggestion } from './itinerary.service';

describe('ItinerarySuggestionsComponent', () => {
  let fixture: ComponentFixture<ItinerarySuggestionsComponent>;
  let httpMock: HttpTestingController;

  const url = `${environment.travelApiUrl}/travelers/me/itinerary-suggestions`;

  const chain: ItinerarySuggestion = {
    stops: [
      { destinationId: 'd1', name: 'Athens', country: 'Greece', startDate: '2027-03-01', endDate: '2027-03-05', price: 0, score: 4 },
      { destinationId: 'd2', name: 'Rome', country: 'Italy', startDate: '2027-03-06', endDate: '2027-03-10', price: 100, score: 6 },
    ],
    score: 10,
    totalDurationMinutes: 90,
    reasons: ['Athens: same country (Greece) as "X" (+3)', 'Rome: shares activity \'museum\' with "X" (+1)'],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ItinerarySuggestionsComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(ItinerarySuggestionsComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(rows: ItinerarySuggestion[]) {
    fixture.detectChanges();
    httpMock.expectOne(url).flush(rows);
    fixture.detectChanges();
  }

  it('shows each chain with its stops linked, total score and duration', () => {
    load([chain]);

    const card = fixture.nativeElement.querySelector('[data-testid="itinerary"]') as HTMLElement;
    expect(card.textContent).toContain('Athens');
    expect(card.textContent).toContain('Rome');
    expect(card.textContent).toContain('90 min');
    expect(card.querySelector('[data-testid="score"]')!.textContent).toContain('score total 10');
    const links = Array.from(card.querySelectorAll('a')) as HTMLAnchorElement[];
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/travels/d1', '/travels/d2']);
  });

  it('shows every reason verbatim', () => {
    load([chain]);

    const reasons = Array.from(fixture.nativeElement.querySelectorAll('[data-testid="reasons"] li')).map(
      (li) => (li as HTMLElement).textContent?.trim(),
    );
    expect(reasons).toEqual(chain.reasons);
  });

  it('renders a hostile stop name as text, never HTML', () => {
    load([{ ...chain, stops: [{ ...chain.stops[0], name: '<img src=x onerror=alert(1)>' }] }]);

    expect(fixture.nativeElement.querySelector('img')).toBeNull();
  });

  it('explains an empty list', () => {
    load([]);

    expect(fixture.nativeElement.querySelector('[data-testid="itinerary-empty"]')).not.toBeNull();
  });

  it('shows an error inside the block only when the call fails', () => {
    fixture.detectChanges();
    httpMock.expectOne(url).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Impossible de charger les itinéraires suggérés.',
    );
  });
});
