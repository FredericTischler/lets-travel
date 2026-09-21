import { HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { FeedbackFormComponent, feedbackErrorMessage } from './feedback-form.component';
import { Feedback } from './feedback.service';

describe('FeedbackFormComponent', () => {
  let fixture: ComponentFixture<FeedbackFormComponent>;
  let component: FeedbackFormComponent;
  let httpMock: HttpTestingController;

  const url = `${environment.travelApiUrl}/destinations/dest-1/feedback`;
  const created: Feedback = {
    id: 'f1',
    travelerId: 't1',
    destinationId: 'dest-1',
    destinationName: 'Lisbon',
    destinationCountry: 'Portugal',
    destinationEndDate: '2026-08-10',
    rating: 4,
    comment: 'Bien',
    createdAt: '2026-08-11T10:00:00Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FeedbackFormComponent, HttpClientTestingModule],
    }).compileComponents();
    fixture = TestBed.createComponent(FeedbackFormComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('destinationId', 'dest-1');
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  const submitButton = () =>
    fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;

  it('offers ratings 1 to 5 and cannot be submitted without a rating', () => {
    expect(fixture.nativeElement.querySelectorAll('input[type="radio"]').length).toBe(5);
    expect(submitButton().disabled).toBe(true);

    component.submit();
    httpMock.expectNone(url);
  });

  it('POSTs the chosen rating and comment and emits the created feedback', () => {
    let saved: Feedback | undefined;
    component.saved.subscribe((f) => (saved = f));

    component['rating'].set(4);
    component['comment'] = 'Bien';
    fixture.detectChanges();
    expect(submitButton().disabled).toBe(false);

    component.submit();
    const req = httpMock.expectOne(url);
    expect(req.request.body).toEqual({ rating: 4, comment: 'Bien' });
    req.flush(created);

    expect(saved).toEqual(created);
    expect(component['saving']()).toBe(false);
  });

  it('explains a 409 (not ended yet / already rated) and a 403 (did not participate)', () => {
    component['rating'].set(5);

    component.submit();
    httpMock.expectOne(url).flush({ error: 'x' }, { status: 409, statusText: 'Conflict' });
    expect(component['error']()).toContain('pas encore terminé');
    expect(component['error']()).toContain('déjà donné votre avis');

    component.submit();
    httpMock.expectOne(url).flush({ error: 'x' }, { status: 403, statusText: 'Forbidden' });
    expect(component['error']()).toContain('participé');
  });

  it('shows the backend message for a validation error, a generic one otherwise', () => {
    expect(
      feedbackErrorMessage(
        new HttpErrorResponse({ status: 400, error: { error: 'rating must be between 1 and 5' } }),
      ),
    ).toBe('rating must be between 1 and 5');
    expect(feedbackErrorMessage(new HttpErrorResponse({ status: 500 }))).toBe(
      'Impossible d’envoyer votre avis.',
    );
  });
});
