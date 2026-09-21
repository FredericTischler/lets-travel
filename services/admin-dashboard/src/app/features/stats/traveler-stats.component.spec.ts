import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { TravelerStatsComponent } from './traveler-stats.component';
import { TravelerStats } from './stats.service';

describe('TravelerStatsComponent', () => {
  let fixture: ComponentFixture<TravelerStatsComponent>;
  let httpMock: HttpTestingController;

  const statsUrl = `${environment.travelApiUrl}/travelers/me/stats`;
  const feedbackUrl = `${environment.travelApiUrl}/travelers/me/feedback`;
  const countUrl = `${environment.identityApiUrl}/reports/count/me-1`;

  const stats: TravelerStats = {
    travelerId: 'me-1',
    partial: false,
    pastParticipationCount: 3,
    upcomingSubscriptionCount: 1,
    cancellationCount: 2,
    feedbackGivenCount: 1,
    preferredPaymentProvider: 'PAYPAL',
    payments: {
      totalCount: 5,
      mostUsedProvider: 'PAYPAL',
      byProvider: [
        { provider: 'PAYPAL', count: 3, totals: { EUR: 900 } },
        { provider: 'MANUAL', count: 2, totals: { EUR: 300, USD: 10 } },
      ],
    },
    pastParticipations: [
      { destinationId: 'd1', name: 'Lisbon', country: 'Portugal', startDate: '2026-05-01', endDate: '2026-05-08', feedbackGiven: true },
      { destinationId: 'd2', name: 'Alps', country: 'Suisse', startDate: '2026-06-01', endDate: '2026-06-08', feedbackGiven: false },
    ],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TravelerStatsComponent, HttpClientTestingModule],
      providers: [provideRouter([]), { provide: AuthService, useValue: { getCurrentUserId: () => 'me-1' } }],
    }).compileComponents();
    fixture = TestBed.createComponent(TravelerStatsComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(s: TravelerStats, reports: number | 'error' = 2, feedback: unknown[] | 'error' = []) {
    fixture.detectChanges();
    httpMock.expectOne(statsUrl).flush(s);
    const count = httpMock.expectOne(countUrl);
    reports === 'error' ? count.flush('x', { status: 500, statusText: 'E' }) : count.flush({ count: reports });
    const fb = httpMock.expectOne(feedbackUrl);
    feedback === 'error' ? fb.flush('x', { status: 500, statusText: 'E' }) : fb.flush(feedback);
    fixture.detectChanges();
  }

  const value = (testId: string) =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"] [data-testid="stat-value"]`).textContent.trim();

  it('shows the participation, cancellation, feedback and report counters', () => {
    load(stats);

    expect(value('kpi-past')).toBe('3');
    expect(value('kpi-upcoming')).toBe('1');
    expect(value('kpi-cancellations')).toBe('2');
    expect(value('kpi-feedback')).toBe('1');
    expect(value('kpi-reports')).toBe('2');
  });

  it('asks identity-service for the reports received by the caller (not in the stats payload)', () => {
    load(stats);

    expect(fixture.nativeElement.querySelector('[data-testid="kpi-reports"]')).not.toBeNull();
  });

  it('shows a dash, not 0, when the report count cannot be read', () => {
    load(stats, 'error');

    expect(value('kpi-reports')).toBe('—');
  });

  it('shows the preferred payment method and the usage per provider, per currency', () => {
    load(stats);

    expect(fixture.nativeElement.querySelector('[data-testid="preferred-provider"]').textContent).toBe('PayPal');
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Paiement manuel');
    expect(text.replace(/[\s  ]+/g, ' ')).toContain('300,00 € · 10,00 $US');
  });

  it('says the payment data is unavailable — and shows no zeros — when payment-service was down', () => {
    load({ ...stats, partial: true, preferredPaymentProvider: null, payments: null });

    expect(fixture.nativeElement.querySelector('[data-testid="payments-unavailable"]').textContent).toContain(
      'indisponibles',
    );
    expect(fixture.nativeElement.querySelector('[data-testid="preferred-provider"]')).toBeNull();
    // The graph counters are still shown.
    expect(value('kpi-past')).toBe('3');
  });

  it('says there is no payment yet, distinctly from unavailable', () => {
    load({ ...stats, preferredPaymentProvider: null, payments: { totalCount: 0, byProvider: [], mostUsedProvider: null } });

    expect(fixture.nativeElement.querySelector('[data-testid="payments-none"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="payments-unavailable"]')).toBeNull();
  });

  it('lists past participations and asks for feedback only where none was given', () => {
    load(stats);

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="participation"]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Donné');
    expect(rows[1].querySelector('[data-testid="give-feedback-link"]').getAttribute('href')).toBe('/travels/d2');
  });

  it('shows "vos avis" when they can be read, and hides the block otherwise', () => {
    load(stats, 2, [
      {
        id: 'f1', travelerId: 'me-1', destinationId: 'd1', destinationName: 'Lisbon', destinationCountry: 'Portugal',
        destinationEndDate: '2026-05-08', rating: 5, comment: 'Top', createdAt: '2026-05-09T10:00:00Z',
      },
    ]);
    expect(fixture.nativeElement.textContent).toContain('Vos avis');
    expect(fixture.nativeElement.textContent).toContain('Top');
  });

  it('hides "vos avis" when they cannot be loaded, without breaking the page', () => {
    load(stats, 2, 'error');

    expect(fixture.nativeElement.textContent).not.toContain('Vos avis');
    expect(value('kpi-past')).toBe('3');
  });

  it('shows an error when the statistics cannot be loaded', () => {
    fixture.detectChanges();
    httpMock.expectOne(statsUrl).flush('boom', { status: 500, statusText: 'E' });
    httpMock.expectOne(countUrl).flush({ count: 0 });
    httpMock.expectOne(feedbackUrl).flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Impossible de charger vos statistiques.',
    );
  });
});
