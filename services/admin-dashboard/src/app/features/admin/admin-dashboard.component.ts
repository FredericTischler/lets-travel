import { NgTemplateOutlet } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';

import { formatMoneyMap, formatNumber, formatRating } from '../../shared/format';
import { extractErrorMessage } from '../../shared/http-error';
import { Page } from '../../shared/pagination';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BarChartComponent } from '../../shared/ui/bar-chart/bar-chart.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { PaginatorComponent } from '../../shared/ui/paginator/paginator.component';
import { StatTileComponent } from '../../shared/ui/stat-tile/stat-tile.component';
import { DASHBOARD_MONTH_CHOICES } from '../manager/manager-dashboard.component';
import { FeedbackListComponent } from '../feedback/feedback-list.component';
import { ReportService } from '../reports/report.service';
import { incomeChartPoints, incomeFormatter } from '../stats/income-chart';
import { AdminDashboard, RankingEntry, StatsService } from '../stats/stats.service';
import { TravelRowsTableComponent } from '../stats/travel-rows-table.component';
import { User, UserService } from '../users/user.service';

const RANKING_PAGE_SIZE = 20;

/**
 * Admin dashboard (`/admin/dashboard`): the overview the subject asks of the Admin
 * — top managers (by score, by rating, by income) and top travels, income for the
 * last months, number of organised travels, the detailed history of past travels
 * with the latest feedback, and the list of every manager ordered by performance
 * score **with their report count**.
 *
 * Sources: `GET /admin/dashboard` (top lists, history, income), `GET /managers/ranking`
 * (the full ordered list — the dashboard's top lists are capped at 5), and
 * identity-service for the rest: `GET /reports/count/{id}` per ranked manager
 * (reports are not part of any travel-service payload; the count does not enter
 * the score) and `GET /users` to show emails instead of ids. Both are best-effort:
 * a failure shows `—` / the id, never hides the page.
 *
 * `partial: true` (payment-service unreachable) shows an explicit "données de
 * revenus indisponibles" notice instead of zeros; scores are then computed
 * without income, which the notice says.
 */
@Component({
  selector: 'app-admin-dashboard',
  imports: [
    FormsModule,
    NgTemplateOutlet,
    RouterLink,
    AlertComponent,
    BarChartComponent,
    CardComponent,
    PaginatorComponent,
    StatTileComponent,
    FeedbackListComponent,
    TravelRowsTableComponent,
  ],
  templateUrl: './admin-dashboard.component.html',
})
export class AdminDashboardComponent implements OnInit {
  private readonly statsService = inject(StatsService);
  private readonly reportService = inject(ReportService);
  private readonly userService = inject(UserService);

  protected readonly data = signal<AdminDashboard | null>(null);
  protected readonly ranking = signal<RankingEntry[] | null>(null);
  protected readonly rankingPage = signal<Page<RankingEntry> | null>(null);
  protected readonly rankingLoading = signal(false);
  protected readonly reportCounts = signal<Record<string, number | null>>({});
  protected readonly emailsById = signal<Record<string, string>>({});
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly months = signal<number>(6);
  protected readonly monthChoices = DASHBOARD_MONTH_CHOICES;

  protected readonly chartPoints = computed(() => incomeChartPoints(this.data()?.income));
  protected readonly chartFormat = computed(() => incomeFormatter(this.data()?.income));
  protected readonly money = formatMoneyMap;
  protected readonly rating = formatRating;
  protected readonly num = formatNumber;

  ngOnInit(): void {
    this.load();
  }

  protected setMonths(value: number): void {
    this.months.set(Number(value));
    this.load();
  }

  /** Email of a manager when known, otherwise the raw id. */
  protected who(managerId: string): string {
    return this.emailsById()[managerId] ?? managerId;
  }

  protected reports(managerId: string): string {
    const count = this.reportCounts()[managerId];
    return count === undefined || count === null ? '—' : String(count);
  }

  private load(): void {
    this.loading.set(this.data() === null);
    this.error.set(null);

    this.statsService.adminDashboard(this.months()).subscribe({
      next: (data) => {
        this.data.set(data);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger le tableau de bord.'));
        this.loading.set(false);
      },
    });

    // The ranking, the report counts and the emails are secondary: each failure is isolated.
    this.loadRanking(0);

    this.userService
      .list()
      .pipe(catchError(() => of<User[]>([])))
      .subscribe((users) =>
        this.emailsById.set(Object.fromEntries(users.map((user) => [user.id, user.email]))),
      );
  }

  /** Only re-fetches the ranking table, not the whole dashboard — kept snappy when paging. */
  protected changeRankingPage(pageIndex: number): void {
    this.loadRanking(pageIndex);
  }

  private loadRanking(pageIndex: number): void {
    this.rankingLoading.set(true);
    this.statsService
      .ranking(pageIndex, RANKING_PAGE_SIZE)
      .pipe(catchError(() => of<Page<RankingEntry> | null>(null)))
      .subscribe((page) => {
        this.rankingLoading.set(false);
        this.rankingPage.set(page);
        this.ranking.set(page?.content ?? null);
        if (page && page.content.length > 0) {
          forkJoin(
            Object.fromEntries(
              page.content.map((entry) => [
                entry.managerId,
                this.reportService.countFor(entry.managerId).pipe(catchError(() => of(null))),
              ]),
            ),
          ).subscribe((counts) => this.reportCounts.set(counts));
        }
      });
  }
}
