import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { Destination } from '../destinations/destination.service';
import {
  AUTOCOMPLETE_DEBOUNCE_MS,
  AUTOCOMPLETE_MIN_CHARS,
  TravelListComponent,
} from './travel-list.component';

describe('TravelListComponent', () => {
  let fixture: ComponentFixture<TravelListComponent>;
  let component: TravelListComponent;
  let httpMock: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  const base = `${environment.travelApiUrl}/destinations`;

  const lisbon: Destination = {
    id: 'dest-1',
    name: 'Lisbon',
    country: 'Portugal',
    startDate: '2027-01-10',
    endDate: '2027-01-20',
    durationDays: 11,
    managerId: 'manager-1',
    price: 1200,
    capacity: 20,
    activities: [],
    accommodations: [],
    createdAt: '2026-01-01T00:00:00Z',
  };
  const paris: Destination = { ...lisbon, id: 'dest-2', name: 'Paris', country: 'France' };

  beforeEach(async () => {
    vi.useFakeTimers();
    await TestBed.configureTestingModule({
      imports: [TravelListComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(TravelListComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    // The recommendations and itinerary-suggestions blocks (their own specs cover them) ask
    // for the caller's data on init.
    httpMock
      .match(`${environment.travelApiUrl}/travelers/me/recommendations?limit=6`)
      .forEach((request) => request.flush([]));
    httpMock
      .match(`${environment.travelApiUrl}/travelers/me/itinerary-suggestions`)
      .forEach((request) => request.flush([]));
    httpMock.verify();
    vi.useRealTimers();
  });

  function init(travels: Destination[] = [lisbon, paris]) {
    fixture.detectChanges();
    httpMock.expectOne(base).flush(travels);
    fixture.detectChanges();
  }

  function type(value: string) {
    component['onQueryChange'](value);
  }

  function pending(path: 'search' | 'autocomplete') {
    return httpMock.match((r) => r.url === `${base}/${path}`);
  }

  function cardTitles(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('article h2') as NodeListOf<HTMLElement>).map(
      (h) => h.textContent?.trim() ?? '',
    );
  }

  it('loads and renders every travel on init', () => {
    init();

    expect(cardTitles()).toEqual(['Lisbon', 'Paris']);
    expect(fixture.nativeElement.textContent).toContain('1,200.00 €');
  });

  it('debounces autocomplete: rapid typing produces a single call with the last prefix', () => {
    init();

    type('l');
    type('li');
    type('lis');
    vi.advanceTimersByTime(AUTOCOMPLETE_DEBOUNCE_MS - 50);
    expect(pending('autocomplete')).toHaveLength(0);

    vi.advanceTimersByTime(60);
    const calls = pending('autocomplete');
    expect(calls).toHaveLength(1);
    expect(calls[0].request.params.get('prefix')).toBe('lis');
  });

  it(`does not call autocomplete below ${AUTOCOMPLETE_MIN_CHARS} characters`, () => {
    init();

    type('l');
    vi.advanceTimersByTime(AUTOCOMPLETE_DEBOUNCE_MS + 10);

    expect(pending('autocomplete')).toHaveLength(0);
  });

  it('shows the suggestions and opens the chosen destination', () => {
    init();

    type('lis');
    vi.advanceTimersByTime(AUTOCOMPLETE_DEBOUNCE_MS + 10);
    pending('autocomplete')[0].flush([{ id: 'dest-1', name: 'Lisbon', country: 'Portugal' }]);
    fixture.detectChanges();

    const option = fixture.nativeElement.querySelector('[role="option"]') as HTMLElement;
    expect(option.textContent).toContain('Lisbon');
    option.dispatchEvent(new MouseEvent('mousedown'));

    expect(navigate).toHaveBeenCalledWith(['/travels', 'dest-1']);
  });

  it('supports the keyboard: ArrowDown then Enter opens the highlighted suggestion', () => {
    init();
    type('lis');
    vi.advanceTimersByTime(AUTOCOMPLETE_DEBOUNCE_MS + 10);
    pending('autocomplete')[0].flush([
      { id: 'dest-1', name: 'Lisbon', country: 'Portugal' },
      { id: 'dest-9', name: 'Lisburn', country: 'UK' },
    ]);

    component['onKeydown'](new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    component['onKeydown'](new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    component['onKeydown'](new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(navigate).toHaveBeenCalledWith(['/travels', 'dest-9']);
  });

  it('search() calls the Elasticsearch search endpoint and shows only the results', () => {
    init();

    type('lisbon');
    component['search']();
    const req = pending('search')[0];
    expect(req.request.params.get('q')).toBe('lisbon');
    req.flush([lisbon]);
    fixture.detectChanges();

    expect(cardTitles()).toEqual(['Lisbon']);
    expect(fixture.nativeElement.textContent).toContain('1 résultat(s)');
  });

  it('an empty query goes back to the plain list instead of searching', () => {
    init();

    type('   ');
    component['search']();

    expect(pending('search')).toHaveLength(0);
    httpMock.expectOne(base).flush([lisbon, paris]);
  });

  it('falls back to the plain list with a notice when search answers 503', () => {
    init([lisbon]);

    type('paris');
    component['search']();
    pending('search')[0].flush({ error: 'Search unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });
    httpMock.expectOne(base).flush([lisbon, paris]);
    fixture.detectChanges();

    expect(cardTitles()).toEqual(['Lisbon', 'Paris']);
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('indisponible');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(component['activeSearch']()).toBeNull();
  });

  it('shows the notice, without breaking, when autocomplete answers 503', () => {
    init();

    type('lis');
    vi.advanceTimersByTime(AUTOCOMPLETE_DEBOUNCE_MS + 10);
    pending('autocomplete')[0].flush('down', { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('indisponible');
    expect(fixture.nativeElement.querySelector('[role="option"]')).toBeNull();
    expect(cardTitles()).toEqual(['Lisbon', 'Paris']);
  });

  it('reports a genuine search failure as an error, keeping the current list', () => {
    init();

    type('lisbon');
    component['search']();
    pending('search')[0].flush({ error: 'boom', status: 500 }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('boom');
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
  });

  it('shows an error when the catalogue cannot be loaded', () => {
    fixture.detectChanges();
    httpMock.expectOne(base).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Impossible de charger les voyages.',
    );
  });

  it('escapes travel names instead of interpreting them as HTML', () => {
    init([{ ...lisbon, name: '<b id="pwn">Bold</b>' }]);

    expect(fixture.nativeElement.querySelector('#pwn')).toBeNull();
    expect(cardTitles()[0]).toBe('<b id="pwn">Bold</b>');
  });
});
