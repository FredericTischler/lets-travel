import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { ItineraryService } from './itinerary.service';

describe('ItineraryService', () => {
  let service: ItineraryService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(ItineraryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('mine() GETs the caller’s itinerary suggestions', () => {
    service.mine().subscribe();

    const req = httpMock.expectOne(`${environment.travelApiUrl}/travelers/me/itinerary-suggestions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
