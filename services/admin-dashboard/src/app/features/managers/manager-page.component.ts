import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { formatRating } from '../../shared/format';
import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { RatingComponent } from '../../shared/ui/rating/rating.component';
import { StatTileComponent } from '../../shared/ui/stat-tile/stat-tile.component';
import { REPORT_REASON_MAX_LENGTH, ReportService } from '../reports/report.service';
import { ManagerStats, StatsService } from '../stats/stats.service';

/**
 * Public page of a Travel Manager (`/managers/:id`), the "Travel Manager page" of
 * the traveler in the subject: statistics, ratings of past travels and the
 * number of reports received (transparency and accountability), with the same
 * "signaler" flow as the travel detail.
 *
 * Aggregates only: the backend gives no individual feedback or traveler id to a
 * traveler (`GET /managers/{id}/stats`), and the manager's identity is an id —
 * emails are not exposed to travelers. An unknown id answers zeros, not 404
 * (the backend cannot tell), which the page states rather than hiding.
 * The report count comes from identity-service (`GET /reports/count/{id}`).
 */
@Component({
  selector: 'app-manager-page',
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    AlertComponent,
    ButtonComponent,
    CardComponent,
    RatingComponent,
    StatTileComponent,
  ],
  templateUrl: './manager-page.component.html',
})
export class ManagerPageComponent implements OnInit {
  private readonly statsService = inject(StatsService);
  private readonly reportService = inject(ReportService);
  private readonly authService = inject(AuthService);

  /** Route param `:id` (bound through `withComponentInputBinding`). */
  readonly id = input.required<string>();

  protected readonly stats = signal<ManagerStats | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly reportCount = signal<number | null>(null);

  protected readonly rating = formatRating;
  protected readonly reasonMaxLength = REPORT_REASON_MAX_LENGTH;
  protected readonly reportOpen = signal(false);
  protected reportReason = '';
  protected readonly reporting = signal(false);
  protected readonly reportError = signal<string | null>(null);
  protected readonly reportSent = signal(false);

  /** A user cannot report themselves (the backend answers 400). */
  protected readonly canReport = computed(() => this.id() !== this.authService.getCurrentUserId());
  protected readonly hasActivity = computed(() => {
    const s = this.stats();
    return s !== null && (s.activeTravels > 0 || s.feedbackCount > 0);
  });

  ngOnInit(): void {
    this.statsService.managerStats(this.id()).subscribe({
      next: (stats) => {
        this.stats.set(stats);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger cette page organisateur.'));
        this.loading.set(false);
      },
    });
    this.loadReportCount();
  }

  toggleReport(): void {
    this.reportOpen.update((open) => !open);
    this.reportError.set(null);
  }

  submitReport(): void {
    const reason = this.reportReason.trim();
    if (reason === '') {
      return;
    }
    this.reporting.set(true);
    this.reportError.set(null);
    this.reportService.create(this.id(), reason).subscribe({
      next: () => {
        this.reporting.set(false);
        this.reportSent.set(true);
        this.reportOpen.set(false);
        this.reportReason = '';
        this.loadReportCount();
      },
      error: (err: unknown) => {
        this.reporting.set(false);
        this.reportError.set(extractErrorMessage(err, 'Impossible d’envoyer ce signalement.'));
      },
    });
  }

  private loadReportCount(): void {
    this.reportService.countFor(this.id()).subscribe({
      next: (count) => this.reportCount.set(count),
      // Informative only: omitted when identity-service does not answer.
      error: () => this.reportCount.set(null),
    });
  }
}
