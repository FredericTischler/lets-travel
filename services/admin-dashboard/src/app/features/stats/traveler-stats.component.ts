import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { formatMoneyMap } from '../../shared/format';
import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { LoadingComponent } from '../../shared/ui/loading/loading.component';
import { StatTileComponent } from '../../shared/ui/stat-tile/stat-tile.component';
import { FeedbackListComponent } from '../feedback/feedback-list.component';
import { Feedback, FeedbackService } from '../feedback/feedback.service';
import { PAYMENT_PROVIDER_LABELS } from '../payments/payment.service';
import { ReportService } from '../reports/report.service';
import { StatsService, TravelerStats } from './stats.service';

/**
 * "Mes statistiques": the traveler's personal statistics of the subject — past
 * participation, subscription cancellations, feedback given, report count and
 * preferred payment methods — plus the history and "vos avis".
 *
 * Sources: travel-service `GET /travelers/me/stats` (graph + payment summary),
 * `GET /travelers/me/feedback`, and identity-service
 * `GET /reports/count/{self}` for the reports received (not part of the stats
 * payload). The last two are best-effort: a failure hides their block, not the page.
 *
 * `partial: true` means payment-service was unreachable: the payment block says
 * so instead of showing zeros.
 */
@Component({
  selector: 'app-traveler-stats',
  imports: [
    DatePipe,
    RouterLink,
    AlertComponent,
    BadgeComponent,
    CardComponent,
    LoadingComponent,
    StatTileComponent,
    FeedbackListComponent,
  ],
  templateUrl: './traveler-stats.component.html',
})
export class TravelerStatsComponent implements OnInit {
  private readonly statsService = inject(StatsService);
  private readonly feedbackService = inject(FeedbackService);
  private readonly reportService = inject(ReportService);
  private readonly authService = inject(AuthService);

  protected readonly stats = signal<TravelerStats | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly reportCount = signal<number | null>(null);
  protected readonly myFeedback = signal<Feedback[] | null>(null);

  protected readonly providerLabels = PAYMENT_PROVIDER_LABELS;
  protected readonly money = formatMoneyMap;

  ngOnInit(): void {
    this.statsService.travelerStats().subscribe({
      next: (stats) => {
        this.stats.set(stats);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger vos statistiques.'));
        this.loading.set(false);
      },
    });

    const self = this.authService.getCurrentUserId();
    if (self) {
      this.reportService.countFor(self).subscribe({
        next: (count) => this.reportCount.set(count),
        error: () => this.reportCount.set(null),
      });
    }

    this.feedbackService.mine().subscribe({
      next: (rows) => this.myFeedback.set(rows),
      error: () => this.myFeedback.set(null),
    });
  }
}
