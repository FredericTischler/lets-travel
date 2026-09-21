import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { ManagerDashboard } from '../stats/stats.service';
import { ManagerDashboardComponent } from './manager-dashboard.component';

describe('ManagerDashboardComponent', () => {
  let fixture: ComponentFixture<ManagerDashboardComponent>;
  let component: ManagerDashboardComponent;
  let httpMock: HttpTestingController;

  const path = `${environment.travelApiUrl}/managers/me/dashboard`;
  const norm = (text: string | null | undefined) => (text ?? '').replace(/[\s  ]+/g, ' ').trim();

  const dashboard: ManagerDashboard = {
    managerId: 'me-1',
    partial: false,
    trips: { organized: 5, past: 2, ongoing: 1, upcoming: 2 },
    travelers: 12,
    rating: { average: 4.2, feedbackCount: 8 },
    income: {
      referenceCurrency: 'EUR',
      totals: { EUR: 3400, USD: 50 },
      amount: 3400,
      months: 6,
      windowTotals: { EUR: 1700 },
      windowAmount: 1700,
      byMonth: [
        { month: '2026-08', totals: { EUR: 500 }, amount: 500 },
        { month: '2026-09', totals: { EUR: 1200 }, amount: 1200 },
      ],
    },
    travels: [
      {
        destinationId: 'd1', managerId: 'me-1', name: 'Lisbon', country: 'Portugal', startDate: '2026-05-01', endDate: '2026-05-08',
        status: 'PAST', capacity: 20, subscribers: 15, feedbackCount: 8, averageRating: 4.2, dampedRating: 4.0,
        income: { EUR: 3400 }, incomeAmount: 3400,
      },
    ],
    recentFeedback: [
      {
        id: 'f1', travelerId: 't1', destinationId: 'd1', destinationName: 'Lisbon', destinationCountry: 'Portugal',
        destinationEndDate: '2026-05-08', rating: 5, comment: '<b>Top</b>', createdAt: '2026-05-09T10:00:00Z',
      },
    ],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ManagerDashboardComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(ManagerDashboardComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(d: ManagerDashboard = dashboard) {
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url === path);
    req.flush(d);
    fixture.detectChanges();
    return req;
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const tile = (id: string) => norm(el().querySelector(`[data-testid="${id}"]`)!.textContent);

  it('shows income, number of trips, number of travelers and average rating', () => {
    load();

    expect(tile('kpi-income')).toContain('3 400,00 €');
    expect(tile('kpi-income')).toContain('50,00 $US');
    expect(tile('kpi-trips')).toContain('5');
    expect(tile('kpi-trips')).toContain('2 terminés · 1 en cours · 2 à venir');
    expect(tile('kpi-travelers')).toContain('12');
    expect(tile('kpi-rating')).toContain('4,2 / 5');
    expect(tile('kpi-rating')).toContain('8 avis');
  });

  it('draws the income per month, in the reference currency, with a table twin', () => {
    load();

    expect(el().querySelectorAll('[data-testid="chart-column"]')).toHaveLength(2);
    const rows = Array.from(el().querySelectorAll('app-bar-chart details tbody tr')).map((r) => norm(r.textContent));
    expect(rows[1]).toContain('1 200 €');
  });

  it('shows the per-travel table with subscribers / capacity, rating and income', () => {
    load();

    const row = norm(el().querySelector('[data-testid="travel-row"]')!.textContent);
    expect(row).toContain('Lisbon');
    expect(row).toContain('15 / 20');
    expect(row).toContain('4,2 sur 5');
    expect(row).toContain('3 400,00 €');
    expect(el().querySelector('[data-testid="travel-row"] a[href="/manager/travels/d1/feedback"]')).not.toBeNull();
  });

  it('shows the latest feedback with the comment as literal text', () => {
    load();

    const comment = el().querySelector('[data-testid="feedback-comment"]')!;
    expect(comment.textContent).toContain('<b>Top</b>');
    expect(comment.querySelector('b')).toBeNull();
  });

  it('shows "données de revenus indisponibles" — not zeros — when payment-service was down', () => {
    load({
      ...dashboard,
      partial: true,
      income: null,
      travels: dashboard.travels.map((t) => ({ ...t, income: null, incomeAmount: null })),
    });

    expect(el().querySelector('[data-testid="income-unavailable"]')!.textContent).toContain('revenus indisponibles');
    expect(tile('kpi-income')).toContain('Indisponible');
    expect(el().querySelector('[data-testid="chart-column"]')).toBeNull();
    expect(norm(el().querySelector('[data-testid="row-income"]')!.textContent)).toBe('indisponible');
    // What the graph knows is still shown.
    expect(tile('kpi-trips')).toContain('5');
  });

  it('reloads with the chosen income window', () => {
    load();

    component['setMonths'](12);
    const req = httpMock.expectOne((r) => r.url === path);
    expect(req.request.params.get('months')).toBe('12');
    req.flush(dashboard);
  });

  it('asks for the caller’s own dashboard by default, and passes ?managerId= for an admin', () => {
    const own = load();
    expect(own.request.params.has('managerId')).toBe(false);

    const other = TestBed.createComponent(ManagerDashboardComponent);
    other.componentRef.setInput('managerId', 'manager-9');
    other.detectChanges();
    const req = httpMock.expectOne((r) => r.url === path);
    expect(req.request.params.get('managerId')).toBe('manager-9');
    req.flush(dashboard);
  });

  it('shows an error when the dashboard cannot be loaded', () => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === path).flush({ error: 'forbidden' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')!.textContent).toContain('forbidden');
  });
});
