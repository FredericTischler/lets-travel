import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { Feedback } from '../feedback/feedback.service';
import { TravelFeedbackComponent } from './travel-feedback.component';

describe('TravelFeedbackComponent', () => {
  let fixture: ComponentFixture<TravelFeedbackComponent>;
  let httpMock: HttpTestingController;

  const feedbackUrl = `${environment.travelApiUrl}/destinations/d1/feedback`;
  const travelUrl = `${environment.travelApiUrl}/destinations/d1`;

  const rows: Feedback[] = [5, 3].map((rating, i) => ({
    id: `f${i}`, travelerId: `traveler-${i}`, destinationId: 'd1', destinationName: 'Lisbon', destinationCountry: 'Portugal',
    destinationEndDate: '2026-05-08', rating, comment: `commentaire ${i}`, createdAt: '2026-05-09T10:00:00Z',
  }));

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TravelFeedbackComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(TravelFeedbackComponent);
    fixture.componentRef.setInput('id', 'd1');
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('shows the feedback of the travel with the average and the author ids', () => {
    fixture.detectChanges();
    httpMock.expectOne(feedbackUrl).flush(rows);
    httpMock.expectOne(travelUrl).flush({ id: 'd1', name: 'Lisbon' });
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')!.textContent).toContain('Lisbon');
    expect(el.querySelector('[data-testid="summary"]')!.textContent).toContain('2 avis');
    expect(el.querySelector('[data-testid="summary"]')!.textContent).toContain('4 sur 5');
    expect(el.textContent).toContain('traveler-0');
    expect(el.textContent).toContain('commentaire 1');
  });

  it('still shows the feedback when the travel title cannot be loaded', () => {
    fixture.detectChanges();
    httpMock.expectOne(feedbackUrl).flush(rows);
    httpMock.expectOne(travelUrl).flush('x', { status: 500, statusText: 'E' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('commentaire 0');
  });

  it('explains a 403 (another manager’s travel)', () => {
    fixture.detectChanges();
    httpMock.expectOne(feedbackUrl).flush({ error: 'x' }, { status: 403, statusText: 'Forbidden' });
    // forkJoin cancels the pending title request as soon as the feedback call fails.
    httpMock.match(travelUrl);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'avis de vos propres voyages',
    );
  });

  it('says so when the travel has no feedback yet', () => {
    fixture.detectChanges();
    httpMock.expectOne(feedbackUrl).flush([]);
    httpMock.expectOne(travelUrl).flush({ id: 'd1', name: 'Lisbon' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="feedback-empty"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="summary"]').textContent).toContain('0 avis');
  });
});
