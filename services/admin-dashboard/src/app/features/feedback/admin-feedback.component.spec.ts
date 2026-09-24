import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { AdminFeedbackComponent } from './admin-feedback.component';
import { Feedback } from './feedback.service';

describe('AdminFeedbackComponent', () => {
  let fixture: ComponentFixture<AdminFeedbackComponent>;
  let component: AdminFeedbackComponent;
  let httpMock: HttpTestingController;

  const url = `${environment.travelApiUrl}/feedback`;

  function feedback(id: string, rating: number, createdAt: string, comment: string | null = `avis ${id}`): Feedback {
    return {
      id, travelerId: `traveler-${id}`, destinationId: 'd1', destinationName: 'Lisbon', destinationCountry: 'Portugal',
      destinationEndDate: '2026-05-08', rating, comment, createdAt,
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminFeedbackComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminFeedbackComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(rows: Feedback[]) {
    fixture.detectChanges();
    httpMock
      .expectOne((req) => req.url === url)
      .flush({ content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: 1 });
    fixture.detectChanges();
  }

  const ids = () =>
    Array.from(fixture.nativeElement.querySelectorAll('[data-testid="feedback-comment"]')).map((c) =>
      (c as HTMLElement).textContent?.trim(),
    );

  it('lists every feedback, newest first, with the author id and a summary', () => {
    // Already newest-first, as the real backend returns it (ORDER BY createdAt DESC) — the
    // component trusts that order rather than re-sorting client-side.
    load([feedback('b', 3, '2026-06-01T00:00:00Z'), feedback('a', 5, '2026-05-01T00:00:00Z')]);

    expect(ids()).toEqual(['avis b', 'avis a']);
    expect(fixture.nativeElement.textContent).toContain('traveler-b');
    expect(fixture.nativeElement.querySelector('[data-testid="summary"]').textContent).toContain('2 avis');
    expect(fixture.nativeElement.querySelector('[data-testid="summary"]').textContent).toContain('4 sur 5');
  });

  it('filters by rating', () => {
    load([feedback('a', 5, '2026-05-01T00:00:00Z'), feedback('b', 3, '2026-06-01T00:00:00Z')]);

    component['filter'].set(3);
    fixture.detectChanges();
    expect(ids()).toEqual(['avis b']);

    component['filter'].set(0);
    fixture.detectChanges();
    expect(ids()).toHaveLength(2);
  });

  it('renders a hostile comment as text', () => {
    load([feedback('a', 1, '2026-05-01T00:00:00Z', '<img src=x onerror=alert(1)>')]);

    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(ids()[0]).toBe('<img src=x onerror=alert(1)>');
  });

  it('requests the next page when the paginator asks for it', () => {
    fixture.detectChanges();
    httpMock
      .expectOne((req) => req.url === url)
      .flush({ content: [feedback('a', 5, '2026-05-01T00:00:00Z')], page: 0, size: 20, totalElements: 30, totalPages: 2 });
    fixture.detectChanges();

    const nextButton = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find((b) => b.textContent?.trim() === 'Suivant');
    nextButton!.click();
    fixture.detectChanges();

    const req = httpMock.expectOne((r) => r.url === url);
    expect(req.request.params.get('page')).toBe('1');
    req.flush({ content: [feedback('b', 4, '2026-06-01T00:00:00Z')], page: 1, size: 20, totalElements: 30, totalPages: 2 });
    fixture.detectChanges();

    expect(ids()).toEqual(['avis b']);
  });

  it('shows an error when the list cannot be loaded (e.g. 403 for a non-admin)', () => {
    fixture.detectChanges();
    httpMock
      .expectOne((req) => req.url === url)
      .flush({ error: 'Forbidden' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Forbidden');
  });
});
