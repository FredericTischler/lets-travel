import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { StatsService } from './stats.service';

describe('StatsService', () => {
  let service: StatsService;
  let httpMock: HttpTestingController;
  const base = environment.travelApiUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(StatsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('managerStats() GETs the public stats of one manager', () => {
    service.managerStats('m1').subscribe();
    const req = httpMock.expectOne(`${base}/managers/m1/stats`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('managerDashboard() defaults to the caller: no managerId param', () => {
    service.managerDashboard().subscribe();
    const req = httpMock.expectOne((r) => r.url === `${base}/managers/me/dashboard`);
    expect(req.request.params.keys()).toEqual([]);
    req.flush({});
  });

  it('managerDashboard() passes managerId (admin) and months when given', () => {
    service.managerDashboard({ managerId: 'm1', months: 12 }).subscribe();
    const req = httpMock.expectOne((r) => r.url === `${base}/managers/me/dashboard`);
    expect(req.request.params.get('managerId')).toBe('m1');
    expect(req.request.params.get('months')).toBe('12');
    req.flush({});
  });

  it('ranking(), adminDashboard() and travelerStats() hit their endpoints', () => {
    service.ranking(0, 20).subscribe();
    httpMock
      .expectOne((r) => r.url === `${base}/managers/ranking`)
      .flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 });

    service.adminDashboard(3).subscribe();
    const admin = httpMock.expectOne((r) => r.url === `${base}/admin/dashboard`);
    expect(admin.request.params.get('months')).toBe('3');
    admin.flush({});

    service.travelerStats().subscribe();
    httpMock.expectOne(`${base}/travelers/me/stats`).flush({});
  });
});
