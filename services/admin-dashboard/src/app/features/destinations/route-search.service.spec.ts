import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { RouteSearchResult, RouteSearchService } from './route-search.service';

describe('RouteSearchService', () => {
  let service: RouteSearchService;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.travelApiUrl}/destinations`;

  const reachable: RouteSearchResult = {
    reachable: true,
    hops: [
      {
        destinationId: 'dest-2',
        destinationName: 'Porto',
        destinationCountry: 'Portugal',
        mode: 'TRAIN',
        durationMinutes: 180,
        departureTime: '2027-01-10T08:00:00Z',
        arrivalTime: '2027-01-10T11:00:00Z',
      },
    ],
    totalDurationMinutes: 180,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [RouteSearchService],
    });

    service = TestBed.inject(RouteSearchService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('findRoute() performs a GET /destinations/{fromId}/routes/{toId} without maxHops when omitted', () => {
    let result: RouteSearchResult | undefined;

    service.findRoute('dest-1', 'dest-2').subscribe((r) => (result = r));

    const req = httpMock.expectOne(`${baseUrl}/dest-1/routes/dest-2`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush(reachable);

    expect(result).toEqual(reachable);
  });

  it('findRoute() sends maxHops as a query param when given', () => {
    service.findRoute('dest-1', 'dest-2', 2).subscribe();

    const req = httpMock.expectOne((r) => r.url === `${baseUrl}/dest-1/routes/dest-2`);
    expect(req.request.params.get('maxHops')).toBe('2');
    req.flush(reachable);
  });

  it('findRoute() surfaces an unreachable result', () => {
    let result: RouteSearchResult | undefined;

    service.findRoute('dest-1', 'dest-3').subscribe((r) => (result = r));

    const req = httpMock.expectOne(`${baseUrl}/dest-1/routes/dest-3`);
    req.flush({ reachable: false, hops: [], totalDurationMinutes: null });

    expect(result).toEqual({ reachable: false, hops: [], totalDurationMinutes: null });
  });
});
