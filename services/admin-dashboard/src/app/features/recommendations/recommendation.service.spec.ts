import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { RecommendationService } from './recommendation.service';

describe('RecommendationService', () => {
  let service: RecommendationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(RecommendationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('mine() GETs the caller’s recommendations with a limit, never a travelerId', () => {
    service.mine(4).subscribe();

    const req = httpMock.expectOne((r) => r.url === `${environment.travelApiUrl}/travelers/me/recommendations`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('limit')).toBe('4');
    expect(req.request.params.has('travelerId')).toBe(false);
    req.flush([]);
  });
});
