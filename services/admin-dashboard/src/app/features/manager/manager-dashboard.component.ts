import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { formatMoneyMap, formatRating } from '../../shared/format';
import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BarChartComponent } from '../../shared/ui/bar-chart/bar-chart.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { StatTileComponent } from '../../shared/ui/stat-tile/stat-tile.component';
import { FeedbackListComponent } from '../feedback/feedback-list.component';
import { incomeChartPoints, incomeFormatter } from '../stats/income-chart';
import { ManagerDashboard, StatsService } from '../stats/stats.service';
import { TravelRowsTableComponent } from '../stats/travel-rows-table.component';

/** Windows offered for the income chart (the backend keeps `months` within 1..24). */
export const DASHBOARD_MONTH_CHOICES = [3, 6, 12] as const;

/**
 * Travel Manager dashboard (`/manager/dashboard`): the "key statistics linked to
 * their travels" of the subject — income, number of trips, number of travelers,
 * average rating — the income per month as a chart (with a table twin), the
 * per-travel table (subscribers, rating, income) and the latest feedback.
 * An admin reaches another manager's dashboard with `?managerId=`.
 *
 * `partial: true` means payment-service was unreachable: the money tiles and
 * the chart say "données de revenus indisponibles" instead of showing zeros,
 * while everything the graph knows is still shown.
 */
@Component({
  selector: 'app-manager-dashboard',
  imports: [
    FormsModule,
    RouterLink,
    AlertComponent,
    BarChartComponent,
    CardComponent,
    StatTileComponent,
    FeedbackListComponent,
    TravelRowsTableComponent,
  ],
  templateUrl: './manager-dashboard.component.html',
})
export class ManagerDashboardComponent implements OnInit {
  private readonly statsService = inject(StatsService);

  /** `managerId` query parameter (bound by withComponentInputBinding), admin only. */
  readonly managerId = input<string | undefined>(undefined);

  protected readonly data = signal<ManagerDashboard | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly months = signal<number>(6);
  protected readonly monthChoices = DASHBOARD_MONTH_CHOICES;

  protected readonly chartPoints = computed(() => incomeChartPoints(this.data()?.income));
  protected readonly chartFormat = computed(() => incomeFormatter(this.data()?.income));
  protected readonly money = formatMoneyMap;
  protected readonly rating = formatRating;

  ngOnInit(): void {
    this.load();
  }

  protected setMonths(value: number): void {
    this.months.set(Number(value));
    this.load();
  }

  private load(): void {
    this.loading.set(this.data() === null);
    this.error.set(null);
    this.statsService
      .managerDashboard({ managerId: this.managerId(), months: this.months() })
      .subscribe({
        next: (data) => {
          this.data.set(data);
          this.loading.set(false);
        },
        error: (err: unknown) => {
          this.error.set(extractErrorMessage(err, 'Impossible de charger le tableau de bord.'));
          this.loading.set(false);
        },
      });
  }
}
