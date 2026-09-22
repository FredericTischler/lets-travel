import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { Destination } from './destination.service';
import { RouteSearchComponent } from './route-search.component';

describe('RouteSearchComponent', () => {
  let fixture: ComponentFixture<RouteSearchComponent>;
  let component: RouteSearchComponent;
  let httpMock: HttpTestingController;

  const destinationsUrl = `${environment.travelApiUrl}/destinations`;

  const lisbon: Destination = {
    id: 'dest-1',
    name: 'Lisbon',
    country: 'Portugal',
    startDate: '2027-01-10',
    endDate: '2027-01-20',
    durationDays: 11,
    managerId: null,
    price: 1200,
    capacity: 20,
    activities: [],
    accommodations: [],
    createdAt: '2026-01-01T00:00:00Z',
  };

  const porto: Destination = { ...lisbon, id: 'dest-2', name: 'Porto' };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RouteSearchComponent, HttpClientTestingModule],
    }).compileComponents();

    fixture = TestBed.createComponent(RouteSearchComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function load() {
    fixture.detectChanges();
    httpMock.expectOne(destinationsUrl).flush([lisbon, porto]);
    fixture.detectChanges();
  }

  it('loads the destination list for the origin/target selectors', () => {
    load();

    expect(component['destinations']()).toEqual([lisbon, porto]);
    expect(component['loading']()).toBe(false);
  });

  it('shows a message when the backend refuses to load destinations', () => {
    fixture.detectChanges();
    httpMock.expectOne(destinationsUrl).flush({ error: 'down' }, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(component['loadError']()).toBe('Impossible de charger la liste des destinations.');
  });

  it('searches a route without maxHops when left at the default', () => {
    load();
    component['fromId'] = 'dest-1';
    component['toId'] = 'dest-2';

    component.search();

    const req = httpMock.expectOne((r) => r.url === `${destinationsUrl}/dest-1/routes/dest-2`);
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush({ reachable: true, hops: [], totalDurationMinutes: 0 });

    expect(component['result']()).toEqual({ reachable: true, hops: [], totalDurationMinutes: 0 });
  });

  it('sends maxHops when changed from the default', () => {
    load();
    component['fromId'] = 'dest-1';
    component['toId'] = 'dest-2';
    component['maxHopsInput'] = 2;

    component.search();

    const req = httpMock.expectOne((r) => r.url === `${destinationsUrl}/dest-1/routes/dest-2`);
    expect(req.request.params.get('maxHops')).toBe('2');
    req.flush({ reachable: false, hops: [], totalDurationMinutes: null });
  });

  it('refuses to search when origin and target are the same', () => {
    load();
    component['fromId'] = 'dest-1';
    component['toId'] = 'dest-1';

    component.search();

    expect(component['searchError']()).toBe('Choisissez deux destinations différentes.');
    httpMock.expectNone((r) => r.url.includes('/routes/'));
  });

  it('shows "aucun itinéraire trouvé" when the backend answers unreachable', () => {
    load();
    component['fromId'] = 'dest-1';
    component['toId'] = 'dest-2';

    component.search();
    httpMock
      .expectOne((r) => r.url === `${destinationsUrl}/dest-1/routes/dest-2`)
      .flush({ reachable: false, hops: [], totalDurationMinutes: null });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Aucun itinéraire trouvé.');
  });

  it('displays the ordered hops and total duration when reachable', () => {
    load();
    component['fromId'] = 'dest-1';
    component['toId'] = 'dest-2';

    component.search();
    httpMock.expectOne((r) => r.url === `${destinationsUrl}/dest-1/routes/dest-2`).flush({
      reachable: true,
      hops: [
        {
          destinationId: 'dest-2',
          destinationName: 'Porto',
          destinationCountry: 'Portugal',
          mode: 'TRAIN',
          durationMinutes: 180,
          departureTime: null,
          arrivalTime: null,
        },
      ],
      totalDurationMinutes: 180,
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Porto');
    expect(fixture.nativeElement.textContent).toContain('TRAIN');
    expect(fixture.nativeElement.textContent).toContain('Durée totale : 180 min');
  });
});
