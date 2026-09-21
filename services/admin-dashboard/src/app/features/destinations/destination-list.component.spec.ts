import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { DestinationListComponent } from './destination-list.component';
import { Destination, DestinationCreateInput } from './destination.service';

describe('DestinationListComponent (admin)', () => {
  let fixture: ComponentFixture<DestinationListComponent>;
  let component: DestinationListComponent;
  let httpMock: HttpTestingController;

  const url = `${environment.travelApiUrl}/destinations`;
  const MANAGER = '11111111-1111-4111-8111-111111111111';

  const lisbon: Destination = {
    id: 'dest-1',
    name: 'Lisbon',
    country: 'Portugal',
    startDate: '2027-01-10',
    endDate: '2027-01-20',
    durationDays: 11,
    managerId: MANAGER,
    price: 1200,
    capacity: 20,
    activities: [],
    accommodations: [],
    createdAt: '2026-01-01T00:00:00Z',
  };

  const input: DestinationCreateInput = {
    name: 'Porto',
    country: 'Portugal',
    startDate: '2027-03-01',
    endDate: '2027-03-05',
    price: 450,
    capacity: 12,
    managerId: MANAGER,
    activities: ['Fado'],
    accommodations: [],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DestinationListComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(DestinationListComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    vi.stubGlobal('confirm', vi.fn(() => true));
  });

  afterEach(() => {
    httpMock.verify();
    vi.unstubAllGlobals();
  });

  function load(destinations: Destination[] = [lisbon]) {
    fixture.detectChanges();
    httpMock.expectOne(url).flush(destinations);
    fixture.detectChanges();
  }

  it('lists every destination with price, capacity and manager, and a link to its subscribers', () => {
    load();

    const cells = Array.from(fixture.nativeElement.querySelectorAll('tbody tr.table-row td') as NodeListOf<HTMLElement>).map(
      (td) => td.textContent?.trim(),
    );
    expect(cells).toContain('Lisbon');
    expect(cells).toContain('1,200.00 €');
    expect(cells).toContain('20');
    expect(cells).toContain(MANAGER);
    const link = fixture.nativeElement.querySelector('tbody a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/manager/travels/dest-1/subscribers');
  });

  it('shows placeholders for a legacy destination without manager, price or capacity', () => {
    load([{ ...lisbon, managerId: null, price: null, capacity: null }]);

    const cells = Array.from(fixture.nativeElement.querySelectorAll('tbody tr.table-row td') as NodeListOf<HTMLElement>).map(
      (td) => td.textContent?.trim(),
    );
    expect(cells.filter((c) => c === '—')).toHaveLength(3);
  });

  it('creates a destination with the manager typed by the admin, then reloads', () => {
    load([]);

    component['saveDestination'](input);
    const create = httpMock.expectOne(url);
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(input);
    create.flush(lisbon);
    httpMock.expectOne(url).flush([lisbon]);

    expect(component['saving']()).toBe(false);
  });

  it('edits with a PUT that never carries the manager id', () => {
    load();
    component['startEdit'](lisbon);

    component['saveDestination']({ ...input, name: 'Lisboa' });
    const update = httpMock.expectOne(`${url}/dest-1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).not.toHaveProperty('managerId');
    expect(update.request.body.name).toBe('Lisboa');
    update.flush(lisbon);
    httpMock.expectOne(url).flush([lisbon]);

    expect(component['editingDestination']()).toBeNull();
  });

  it('shows the backend message when the save is refused', () => {
    load([]);

    component['saveDestination'](input);
    httpMock
      .expectOne(url)
      .flush({ error: 'Validation failed — price: must not be null' }, { status: 400, statusText: 'Bad Request' });

    expect(component['saveError']()).toBe('Validation failed — price: must not be null');
  });

  it('deletes after confirmation and reloads', () => {
    load();

    component['deleteDestination'](lisbon);
    const del = httpMock.expectOne(`${url}/dest-1`);
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    httpMock.expectOne(url).flush([]);
  });

  it('loads the outgoing transports of a destination on demand', () => {
    load();

    component['toggleTransports'](lisbon);
    httpMock.expectOne(`${url}/dest-1/transports`).flush([]);

    expect(component['expandedDestinationId']()).toBe('dest-1');
  });
});
