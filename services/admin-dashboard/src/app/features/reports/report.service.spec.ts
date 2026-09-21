import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { Report, ReportService } from './report.service';

describe('ReportService', () => {
  let service: ReportService;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.identityApiUrl}/reports`;

  const report: Report = {
    id: 'r1',
    reporterId: 'u1',
    reportedUserId: 'u2',
    reason: 'No-show',
    status: 'OPEN',
    createdAt: '2026-09-01T10:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(ReportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('create() POSTs the reported user and the reason (never a reporter id)', () => {
    let result: Report | undefined;
    service.create('u2', 'No-show').subscribe((r) => (result = r));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reportedUserId: 'u2', reason: 'No-show' });
    req.flush(report);

    expect(result).toEqual(report);
  });

  it('list() GETs /reports', () => {
    let result: Report[] | undefined;
    service.list().subscribe((r) => (result = r));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('GET');
    req.flush([report]);

    expect(result).toEqual([report]);
  });

  it('updateStatus() PATCHes /reports/{id}/status with the decision', () => {
    let result: Report | undefined;
    service.updateStatus('r1', 'DISMISSED').subscribe((r) => (result = r));

    const req = httpMock.expectOne(`${baseUrl}/r1/status`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ status: 'DISMISSED' });
    req.flush({ ...report, status: 'DISMISSED' });

    expect(result?.status).toBe('DISMISSED');
  });

  it('countFor() unwraps the count of GET /reports/count/{userId}', () => {
    let result: number | undefined;
    service.countFor('u2').subscribe((count) => (result = count));

    const req = httpMock.expectOne(`${baseUrl}/count/u2`);
    expect(req.request.method).toBe('GET');
    req.flush({ count: 3 });

    expect(result).toBe(3);
  });
});
