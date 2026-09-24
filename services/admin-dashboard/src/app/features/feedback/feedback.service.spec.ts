import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { Feedback, FeedbackService } from './feedback.service';

describe('FeedbackService', () => {
  let service: FeedbackService;
  let httpMock: HttpTestingController;

  const base = environment.travelApiUrl;
  const feedback: Feedback = {
    id: 'f1',
    travelerId: 't1',
    destinationId: 'd1',
    destinationName: 'Lisbon',
    destinationCountry: 'Portugal',
    destinationEndDate: '2026-08-10',
    rating: 5,
    comment: 'Superbe',
    createdAt: '2026-08-11T10:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(FeedbackService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('give() POSTs the rating and the trimmed comment, never an author', () => {
    service.give('d1', 4, '  Très bien  ').subscribe();

    const req = httpMock.expectOne(`${base}/destinations/d1/feedback`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ rating: 4, comment: 'Très bien' });
    req.flush(feedback);
  });

  it('give() omits a blank comment (the backend refuses a present-but-blank one)', () => {
    service.give('d1', 3, '   ').subscribe();
    service.give('d1', 3, null).subscribe();

    const requests = httpMock.match(`${base}/destinations/d1/feedback`);
    expect(requests.map((r) => r.request.body)).toEqual([{ rating: 3 }, { rating: 3 }]);
    requests.forEach((r) => r.flush(feedback));
  });

  it('forDestination() GETs the feedback of one travel', () => {
    let result: Feedback[] | undefined;
    service.forDestination('d1').subscribe((rows) => (result = rows));

    const req = httpMock.expectOne(`${base}/destinations/d1/feedback`);
    expect(req.request.method).toBe('GET');
    req.flush([feedback]);
    expect(result).toEqual([feedback]);
  });

  it('mine() GETs /travelers/me/feedback and all() GETs /feedback', () => {
    service.mine().subscribe();
    httpMock.expectOne(`${base}/travelers/me/feedback`).flush([]);

    service.all(0, 20).subscribe();
    httpMock
      .expectOne((r) => r.url === `${base}/feedback`)
      .flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 });
  });
});
