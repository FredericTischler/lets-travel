import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { RecommendationsComponent } from './recommendations.component';
import { Recommendation } from './recommendation.service';

describe('RecommendationsComponent', () => {
  let fixture: ComponentFixture<RecommendationsComponent>;
  let httpMock: HttpTestingController;

  const url = `${environment.travelApiUrl}/travelers/me/recommendations`;

  const algarve: Recommendation = {
    destinationId: 'd1',
    name: 'Algarve Surf Week',
    country: 'Portugal',
    startDate: '2027-03-01',
    endDate: '2027-03-08',
    price: 480,
    score: 18,
    reasons: [
      'same country (Portugal) as "Lisbon Surf Camp", which you rated 5 (+9)',
      '2 activities in common (surf, yoga) with "Lisbon Surf Camp", which you rated 5 (+6)',
      'similar price to "Lisbon Surf Camp", which you rated 5 (+3)',
    ],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RecommendationsComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(RecommendationsComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(rows: Recommendation[]) {
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url === url);
    expect(req.request.params.get('limit')).toBe('6');
    req.flush(rows);
    fixture.detectChanges();
  }

  it('shows each suggestion with its score and links to the travel', () => {
    load([algarve]);

    const card = fixture.nativeElement.querySelector('[data-testid="recommendation"]') as HTMLElement;
    expect(card.textContent).toContain('Algarve Surf Week');
    expect(card.querySelector('[data-testid="score"]')!.textContent).toContain('score 18');
    expect(card.querySelector('a')!.getAttribute('href')).toBe('/travels/d1');
  });

  it('shows every reason verbatim, with its signed points, so the score can be recomputed', () => {
    load([algarve]);

    const reasons = Array.from(fixture.nativeElement.querySelectorAll('[data-testid="reasons"] li')).map(
      (li) => (li as HTMLElement).textContent?.trim(),
    );
    expect(reasons).toEqual(algarve.reasons);
    // 9 + 6 + 3 = 18: the listed points add up to the displayed score.
    const points = reasons.map((r) => Number(/\(([+-]\d+)\)/.exec(r!)![1]));
    expect(points.reduce((a, b) => a + b, 0)).toBe(algarve.score);
  });

  it('renders a hostile destination name or reason as text', () => {
    load([{ ...algarve, name: '<img src=x onerror=alert(1)>', reasons: ['<script>alert(1)</script>'] }]);

    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.querySelector('script')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('<script>alert(1)</script>');
  });

  it('explains an empty list', () => {
    load([]);

    expect(fixture.nativeElement.querySelector('[data-testid="recommendation-empty"]')).not.toBeNull();
  });

  it('shows an error inside the block only when the call fails', () => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === url).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Impossible de charger vos suggestions.',
    );
  });
});
