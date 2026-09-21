import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import {
  AutocompleteSuggestion,
  Destination,
  DestinationCreateInput,
  DestinationInput,
  DestinationService,
} from './destination.service';

describe('DestinationService', () => {
  let service: DestinationService;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.travelApiUrl}/destinations`;

  const sampleDestination: Destination = {
    id: 'dest-1',
    name: 'Lisbon',
    country: 'Portugal',
    startDate: '2026-06-01',
    endDate: '2026-06-05',
    durationDays: 5,
    managerId: 'manager-1',
    price: 1200,
    capacity: 20,
    activities: [{ id: 'act-1', name: 'Tram 28 ride' }],
    accommodations: [
      {
        id: 'acc-1',
        name: 'Hotel Lisboa',
        type: 'HOTEL',
        checkIn: '2026-06-01',
        checkOut: '2026-06-05',
      },
    ],
    createdAt: '2026-01-01T00:00:00Z',
  };

  const sampleInput: DestinationInput = {
    name: 'Lisbon',
    country: 'Portugal',
    startDate: '2026-06-01',
    endDate: '2026-06-05',
    price: 1200,
    capacity: 20,
    activities: ['Tram 28 ride'],
    accommodations: [
      { name: 'Hotel Lisboa', type: 'HOTEL', checkIn: '2026-06-01', checkOut: '2026-06-05' },
    ],
  };

  const sampleCreateInput: DestinationCreateInput = { ...sampleInput, managerId: 'manager-1' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [DestinationService],
    });

    service = TestBed.inject(DestinationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('list() performs a GET /destinations and returns the response', () => {
    let result: Destination[] | undefined;

    service.list().subscribe((destinations) => (result = destinations));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('GET');
    req.flush([sampleDestination]);

    expect(result).toEqual([sampleDestination]);
  });

  it('get() performs a GET /destinations/{id}', () => {
    let result: Destination | undefined;

    service.get('dest-1').subscribe((destination) => (result = destination));

    const req = httpMock.expectOne(`${baseUrl}/dest-1`);
    expect(req.request.method).toBe('GET');
    req.flush(sampleDestination);

    expect(result).toEqual(sampleDestination);
  });

  it('search() performs a GET /destinations/search with the query as `q` param', () => {
    let result: Destination[] | undefined;

    service.search('lis bon & co').subscribe((destinations) => (result = destinations));

    const req = httpMock.expectOne((r) => r.url === `${baseUrl}/search`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('lis bon & co');
    req.flush([sampleDestination]);

    expect(result).toEqual([sampleDestination]);
  });

  it('autocomplete() performs a GET /destinations/autocomplete with the `prefix` param', () => {
    let result: AutocompleteSuggestion[] | undefined;
    const suggestions: AutocompleteSuggestion[] = [{ id: 'dest-1', name: 'Lisbon', country: 'Portugal' }];

    service.autocomplete('lis').subscribe((items) => (result = items));

    const req = httpMock.expectOne((r) => r.url === `${baseUrl}/autocomplete`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('prefix')).toBe('lis');
    req.flush(suggestions);

    expect(result).toEqual(suggestions);
  });

  it('create() performs a POST /destinations with the given body', () => {
    let result: Destination | undefined;

    service.create(sampleCreateInput).subscribe((destination) => (result = destination));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(sampleCreateInput);
    req.flush(sampleDestination);

    expect(result).toEqual(sampleDestination);
  });

  it('update() performs a PUT /destinations/{id} with the given body', () => {
    let result: Destination | undefined;

    service.update('dest-1', sampleInput).subscribe((destination) => (result = destination));

    const req = httpMock.expectOne(`${baseUrl}/dest-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(sampleInput);
    req.flush(sampleDestination);

    expect(result).toEqual(sampleDestination);
  });

  it('delete() performs a DELETE /destinations/{id}', () => {
    let completed = false;

    service.delete('dest-1').subscribe(() => (completed = true));

    const req = httpMock.expectOne(`${baseUrl}/dest-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    expect(completed).toBe(true);
  });
});
