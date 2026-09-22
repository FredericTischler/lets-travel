import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { AdminDashboard, RankingEntry, TravelRow } from '../stats/stats.service';
import { AdminDashboardComponent } from './admin-dashboard.component';

describe('AdminDashboardComponent', () => {
  let fixture: ComponentFixture<AdminDashboardComponent>;
  let httpMock: HttpTestingController;

  const dashboardUrl = `${environment.travelApiUrl}/admin/dashboard`;
  const rankingUrl = `${environment.travelApiUrl}/managers/ranking`;
  const usersUrl = `${environment.identityApiUrl}/users`;
  const countUrl = (id: string) => `${environment.identityApiUrl}/reports/count/${id}`;
  const norm = (text: string | null | undefined) => (text ?? '').replace(/[\s  ]+/g, ' ').trim();

  function entry(rank: number, managerId: string, overrides: Partial<RankingEntry> = {}): RankingEntry {
    return {
      rank, managerId, averageRating: 4.5, dampedRating: 4.2, feedbackCount: 10, activeTravels: 3, subscribers: 20,
      income: { EUR: 1000 }, incomeAmount: 1000, score: 80 - rank, partial: false, ...overrides,
    };
  }

  function travel(id: string, overrides: Partial<TravelRow> = {}): TravelRow {
    return {
      destinationId: id, managerId: 'm1', name: `Travel ${id}`, country: 'France', startDate: '2026-05-01', endDate: '2026-05-08',
      status: 'PAST', capacity: 10, subscribers: 8, feedbackCount: 3, averageRating: 4, dampedRating: 3.8,
      income: { EUR: 800 }, incomeAmount: 800, ...overrides,
    };
  }

  const dashboard: AdminDashboard = {
    partial: false,
    referenceCurrency: 'EUR',
    months: 6,
    totals: {
      managers: 4, organizedTravels: 9, pastTravels: 5, ongoingTravels: 1, upcomingTravels: 3,
      activeTravelers: 30, feedbackCount: 25, averageRating: 4.3,
    },
    income: {
      referenceCurrency: 'EUR', totals: { EUR: 9000 }, amount: 9000, months: 6, windowTotals: { EUR: 4000 }, windowAmount: 4000,
      byMonth: [
        { month: '2026-08', totals: { EUR: 1500 }, amount: 1500 },
        { month: '2026-09', totals: { EUR: 2500 }, amount: 2500 },
      ],
    },
    topManagersByScore: [entry(1, 'm1'), entry(2, 'm2')],
    topManagersByRating: [entry(1, 'm2')],
    topManagersByIncome: [entry(1, 'm1')],
    topTravelsByIncome: [travel('t1')],
    topTravelsByRating: [travel('t2')],
    travelHistory: [travel('t3'), travel('t4')],
    recentFeedback: [
      {
        id: 'f1', travelerId: 'tr1', destinationId: 't3', destinationName: 'Travel t3', destinationCountry: 'France',
        destinationEndDate: '2026-05-08', rating: 2, comment: 'Bof', createdAt: '2026-05-09T10:00:00Z',
      },
    ],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminDashboardComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminDashboardComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  interface Answers {
    dashboard?: AdminDashboard | 'error';
    ranking?: RankingEntry[] | 'error';
    users?: { id: string; email: string }[] | 'error';
    counts?: Record<string, number | 'error'>;
  }

  function load(a: Answers = {}) {
    fixture.detectChanges();
    const d = a.dashboard ?? dashboard;
    const dashboardReq = httpMock.expectOne((r) => r.url === dashboardUrl);
    d === 'error' ? dashboardReq.flush('boom', { status: 500, statusText: 'E' }) : dashboardReq.flush(d);

    const ranking = a.ranking ?? [entry(1, 'm1'), entry(2, 'm2'), entry(3, 'm3')];
    const rankingReq = httpMock.expectOne(rankingUrl);
    ranking === 'error' ? rankingReq.flush('x', { status: 500, statusText: 'E' }) : rankingReq.flush(ranking);
    if (ranking !== 'error') {
      for (const e of ranking) {
        const c = a.counts?.[e.managerId] ?? 0;
        const req = httpMock.expectOne(countUrl(e.managerId));
        c === 'error' ? req.flush('x', { status: 500, statusText: 'E' }) : req.flush({ count: c });
      }
    }
    const users = a.users ?? [{ id: 'm1', email: 'alice@example.com' }];
    const usersReq = httpMock.expectOne(usersUrl);
    users === 'error' ? usersReq.flush('x', { status: 500, statusText: 'E' }) : usersReq.flush(users);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const tile = (id: string) => norm(el().querySelector(`[data-testid="${id}"]`)!.textContent);

  it('shows the organised travels, managers, travelers, income and satisfaction', () => {
    load();

    expect(tile('kpi-travels')).toContain('9');
    expect(tile('kpi-travels')).toContain('5 terminés · 1 en cours · 3 à venir');
    expect(tile('kpi-managers')).toContain('4');
    expect(tile('kpi-travelers')).toContain('30');
    expect(tile('kpi-income')).toContain('9 000,00 €');
    expect(tile('kpi-rating')).toContain('4,3 / 5');
    expect(tile('kpi-rating')).toContain('25 avis');
  });

  it('draws the income of the last months', () => {
    load();

    expect(el().querySelectorAll('[data-testid="chart-column"]')).toHaveLength(2);
  });

  it('shows the three top-manager lists, resolving emails and falling back to ids', () => {
    load();

    const tops = Array.from(el().querySelectorAll('[data-testid="top-manager"]')).map((r) => norm(r.textContent));
    expect(tops).toHaveLength(4);
    expect(tops[0]).toContain('alice@example.com');
    expect(tops[0]).toContain('79 / 100');
    // m2 has no email in GET /users: its id is shown.
    expect(tops[1]).toContain('m2');
  });

  it('shows the top travels, the travel history and the recent feedback', () => {
    load();

    const text = norm(el().textContent);
    expect(text).toContain('Travel t1');
    expect(text).toContain('Travel t2');
    expect(el().querySelectorAll('[data-testid="travel-row"]').length).toBe(4);
    expect(text).toContain('Historique détaillé des voyages terminés');
    expect(text).toContain('Bof');
  });

  it('lists every manager by performance score with their report count', () => {
    load({ counts: { m1: 0, m2: 5, m3: 'error' } });

    const rows = Array.from(el().querySelectorAll('[data-testid="ranking-row"]'));
    expect(rows).toHaveLength(3);
    expect(norm(rows[0].querySelector('[data-testid="ranking-score"]')!.textContent)).toBe('79');
    expect(norm(rows[0].querySelector('[data-testid="ranking-reports"]')!.textContent)).toBe('0');
    expect(norm(rows[1].querySelector('[data-testid="ranking-reports"]')!.textContent)).toBe('5');
    // A failed count is a dash, not a 0.
    expect(norm(rows[2].querySelector('[data-testid="ranking-reports"]')!.textContent)).toBe('—');
    expect(rows[0].querySelector('a[href="/managers/m1"]')).not.toBeNull();
    expect(rows[0].querySelector('a[href="/manager/dashboard?managerId=m1"]')).not.toBeNull();
  });

  it('hides the ranking pagination controls when everything fits on one page', () => {
    load(); // default ranking has 3 entries

    expect(el().querySelector('[data-testid="ranking-pagination"]')).toBeNull();
  });

  it('paginates the full ranking client-side (10 rows per page — the backend does not paginate it)', () => {
    const ranking = Array.from({ length: 25 }, (_, i) => entry(i + 1, `m${i + 1}`));
    load({ ranking });

    const rankingRows = () => el().querySelectorAll('[data-testid="ranking-row"]');
    const nextButton = () =>
      Array.from(el().querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
        (b) => b.textContent?.trim() === 'Suivant',
      )!;
    const previousButton = () =>
      Array.from(el().querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
        (b) => b.textContent?.trim() === 'Précédent',
      )!;

    expect(rankingRows()).toHaveLength(10);
    expect(el().querySelector('[data-testid="ranking-page-info"]')!.textContent).toContain('Page 1 sur 3');
    expect(previousButton().disabled).toBe(true);

    nextButton().click();
    fixture.detectChanges();

    expect(rankingRows()).toHaveLength(10);
    expect(el().querySelector('[data-testid="ranking-page-info"]')!.textContent).toContain('Page 2 sur 3');
    expect(norm(rankingRows()[0].textContent)).toContain('m11');

    nextButton().click();
    fixture.detectChanges();

    expect(rankingRows()).toHaveLength(5);
    expect(el().querySelector('[data-testid="ranking-page-info"]')!.textContent).toContain('Page 3 sur 3');
    expect(nextButton().disabled).toBe(true);
  });

  it('says "données de revenus indisponibles" when payment-service was down, and still serves the rest', () => {
    load({
      dashboard: { ...dashboard, partial: true, income: null, topManagersByIncome: [], topTravelsByIncome: [] },
      ranking: [entry(1, 'm1', { partial: true, income: null, incomeAmount: null })],
    });

    expect(el().querySelector('[data-testid="income-unavailable"]')!.textContent).toContain('revenus indisponibles');
    expect(tile('kpi-income')).toContain('Indisponible');
    expect(el().querySelector('[data-testid="chart-column"]')).toBeNull();
    expect(norm(el().querySelector('[data-testid="ranking-row"]')!.textContent)).toContain('indisponible');
    expect(tile('kpi-travels')).toContain('9');
  });

  it('keeps the dashboard when the full ranking or the user list are unavailable', () => {
    load({ ranking: 'error', users: 'error' });

    expect(el().querySelector('[data-testid="ranking-unavailable"]')).not.toBeNull();
    expect(tile('kpi-travels')).toContain('9');
    // Without emails the top lists show ids.
    expect(norm(el().querySelector('[data-testid="top-manager"]')!.textContent)).toContain('m1');
  });

  it('shows an error when the dashboard itself cannot be loaded', () => {
    load({ dashboard: 'error' });

    expect(el().querySelector('[role="alert"]')!.textContent).toContain('Impossible de charger le tableau de bord.');
  });

  it('escapes travel names and feedback comments', () => {
    load({
      dashboard: {
        ...dashboard,
        travelHistory: [travel('t9', { name: '<img src=x onerror=alert(1)>' })],
        recentFeedback: [{ ...dashboard.recentFeedback[0], comment: '<script>alert(1)</script>' }],
      },
    });

    expect(el().querySelector('img')).toBeNull();
    expect(el().querySelector('script')).toBeNull();
  });
});
