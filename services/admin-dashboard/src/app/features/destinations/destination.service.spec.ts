import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { Destination, DestinationInput, DestinationService } from './destination.service';

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
    activities: ['Tram 28 ride'],
    accommodations: [
      { name: 'Hotel Lisboa', type: 'HOTEL', checkIn: '2026-06-01', checkOut: '2026-06-05' },
    ],
  };

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

  it('create() performs a POST /destinations with the given body', () => {
    let result: Destination | undefined;

    service.create(sampleInput).subscribe((destination) => (result = destination));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(sampleInput);
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
