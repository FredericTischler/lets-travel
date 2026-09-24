import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { BadgeService } from './badge.service';

describe('BadgeService', () => {
  let service: BadgeService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(BadgeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('mine() GETs the caller’s badges', () => {
    service.mine().subscribe();

    const req = httpMock.expectOne(`${environment.travelApiUrl}/travelers/me/badges`);
    expect(req.request.method).toBe('GET');
    req.flush({ destinationsVisited: 0, countriesVisited: 0, reviewsGiven: 0, badges: [] });
  });
});
