import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Page } from '../../shared/pagination';
import { Feedback } from '../feedback/feedback.service';
import { PaymentProvider } from '../payments/payment.service';

/** Money per ISO currency; currencies are never summed together. */
export type MoneyByCurrency = Record<string, number>;

export interface MonthlyIncome {
  /** `YYYY-MM`. */
  month: string;
  totals: MoneyByCurrency;
  /** The reference-currency share only. */
  amount: number;
}

/** travel-service IncomeSummary.java. */
export interface IncomeSummary {
  referenceCurrency: string;
  totals: MoneyByCurrency;
  amount: number;
  months: number;
  windowTotals: MoneyByCurrency;
  windowAmount: number;
  byMonth: MonthlyIncome[];
}

/** travel-service TravelStatsRow.java — one travel of a dashboard. */
export interface TravelRow {
  destinationId: string;
  managerId: string;
  name: string;
  country: string;
  startDate: string;
  endDate: string;
  status: 'UPCOMING' | 'ONGOING' | 'PAST';
  capacity: number | null;
  subscribers: number;
  feedbackCount: number;
  averageRating: number | null;
  dampedRating: number;
  /** `null` when payment-service was unreachable. */
  income: MoneyByCurrency | null;
  incomeAmount: number | null;
}

/** travel-service ManagerRankingEntry.java. */
export interface RankingEntry {
  rank: number;
  managerId: string;
  averageRating: number | null;
  dampedRating: number;
  feedbackCount: number;
  activeTravels: number;
  subscribers: number;
  income: MoneyByCurrency | null;
  incomeAmount: number | null;
  score: number;
  partial: boolean;
}

/** GET /managers/me/dashboard (travel-service ManagerDashboardResponse.java). */
export interface ManagerDashboard {
  managerId: string;
  /** `true`: payment-service was unreachable, every money field is null. */
  partial: boolean;
  trips: { organized: number; past: number; ongoing: number; upcoming: number };
  travelers: number;
  rating: { average: number | null; feedbackCount: number };
  income: IncomeSummary | null;
  travels: TravelRow[];
  recentFeedback: Feedback[];
}

/** GET /admin/dashboard (travel-service AdminDashboardResponse.java). */
export interface AdminDashboard {
  partial: boolean;
  referenceCurrency: string;
  months: number;
  totals: {
    managers: number;
    organizedTravels: number;
    pastTravels: number;
    ongoingTravels: number;
    upcomingTravels: number;
    activeTravelers: number;
    feedbackCount: number;
    averageRating: number | null;
  };
  income: IncomeSummary | null;
  topManagersByScore: RankingEntry[];
  topManagersByRating: RankingEntry[];
  topManagersByIncome: RankingEntry[];
  topTravelsByIncome: TravelRow[];
  topTravelsByRating: TravelRow[];
  travelHistory: TravelRow[];
  recentFeedback: Feedback[];
}

/** GET /managers/{id}/stats — public aggregates only (travel-service ManagerStatsResponse.java). */
export interface ManagerStats {
  managerId: string;
  activeTravels: number;
  pastTravels: number;
  subscribers: number;
  feedbackCount: number;
  averageRating: number | null;
  pastRatings: {
    destinationId: string;
    name: string;
    country: string;
    startDate: string;
    endDate: string;
    feedbackCount: number;
    averageRating: number | null;
  }[];
}

/** GET /travelers/me/stats (travel-service TravelerStatsResponse.java). */
export interface TravelerStats {
  travelerId: string;
  /** `true`: payment-service was unreachable, the payment fields are null. */
  partial: boolean;
  pastParticipationCount: number;
  upcomingSubscriptionCount: number;
  cancellationCount: number;
  feedbackGivenCount: number;
  preferredPaymentProvider: PaymentProvider | null;
  payments: {
    totalCount: number;
    byProvider: { provider: PaymentProvider; count: number; totals: MoneyByCurrency }[];
    mostUsedProvider: PaymentProvider | null;
  } | null;
  pastParticipations: {
    destinationId: string;
    name: string;
    country: string;
    startDate: string;
    endDate: string;
    feedbackGiven: boolean;
  }[];
}

/**
 * Read access to every statistics endpoint of travel-service: the public manager
 * page, the two role dashboards, the manager ranking and the traveler's personal
 * statistics. Report counts are *not* in these payloads — they come from
 * identity-service (`ReportService.countFor`).
 */
@Injectable({ providedIn: 'root' })
export class StatsService {
  private readonly http = inject(HttpClient);

  private readonly base = environment.travelApiUrl;

  /** Public page of a manager (any role): aggregates and per-travel past ratings. */
  managerStats(managerId: string): Observable<ManagerStats> {
    return this.http.get<ManagerStats>(`${this.base}/managers/${managerId}/stats`);
  }

  /** Manager dashboard: the caller's own, or — admin only — `managerId`'s. `months` is kept within 1..24 by the backend. */
  managerDashboard(options: { managerId?: string | null; months?: number } = {}): Observable<ManagerDashboard> {
    const params: Record<string, string | number> = {};
    if (options.managerId) {
      params['managerId'] = options.managerId;
    }
    if (options.months) {
      params['months'] = options.months;
    }
    return this.http.get<ManagerDashboard>(`${this.base}/managers/me/dashboard`, { params });
  }

  /** One page of every manager ordered by performance score (admin only). `page` is 0-based. */
  ranking(page: number, size: number): Observable<Page<RankingEntry>> {
    return this.http.get<Page<RankingEntry>>(`${this.base}/managers/ranking`, { params: { page, size } });
  }

  adminDashboard(months?: number): Observable<AdminDashboard> {
    return this.http.get<AdminDashboard>(`${this.base}/admin/dashboard`, {
      params: months ? { months } : {},
    });
  }

  travelerStats(): Observable<TravelerStats> {
    return this.http.get<TravelerStats>(`${this.base}/travelers/me/stats`);
  }
}
