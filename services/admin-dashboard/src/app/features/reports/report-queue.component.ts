import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { forkJoin, catchError, of } from 'rxjs';

import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { BadgeComponent, BadgeTone } from '../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { User, UserService } from '../users/user.service';
import { Report, ReportDecision, ReportService, ReportStatus } from './report.service';

export type ReportFilter = 'ALL' | ReportStatus;

export const REPORT_STATUS_LABELS: Record<ReportStatus, string> = {
  OPEN: 'Ouvert',
  REVIEWED: 'Examiné',
  DISMISSED: 'Rejeté',
  ACTIONED: 'Sanctionné',
};

const REPORT_STATUS_TONES: Record<ReportStatus, BadgeTone> = {
  OPEN: 'warning',
  REVIEWED: 'info',
  DISMISSED: 'neutral',
  ACTIONED: 'danger',
};

/** The decisions offered on an OPEN report, in display order. */
export const REPORT_DECISIONS: readonly { status: ReportDecision; label: string }[] = [
  { status: 'REVIEWED', label: 'Marquer examiné' },
  { status: 'DISMISSED', label: 'Rejeter' },
  { status: 'ACTIONED', label: 'Sanctionner' },
];

/**
 * Admin review queue for reports filed by travelers
 * (GET /reports, PATCH /reports/{id}/status). A report is created OPEN and
 * moves exactly once to REVIEWED, DISMISSED or ACTIONED; after that the
 * backend refuses any further change (409), so no action is offered on
 * decided reports.
 *
 * User ids are resolved to emails through the admin-only GET /users when it
 * answers; otherwise the raw id is shown. The report `reason` is free text
 * from another user and is only ever interpolated (never bound as HTML).
 */
@Component({
  selector: 'app-report-queue',
  imports: [DatePipe, AlertComponent, BadgeComponent, ButtonComponent, CardComponent],
  templateUrl: './report-queue.component.html',
})
export class ReportQueueComponent implements OnInit {
  private readonly reportService = inject(ReportService);
  private readonly userService = inject(UserService);

  protected readonly decisions = REPORT_DECISIONS;
  protected readonly filters: readonly { value: ReportFilter; label: string }[] = [
    { value: 'ALL', label: 'Tous' },
    { value: 'OPEN', label: REPORT_STATUS_LABELS.OPEN },
    { value: 'REVIEWED', label: REPORT_STATUS_LABELS.REVIEWED },
    { value: 'DISMISSED', label: REPORT_STATUS_LABELS.DISMISSED },
    { value: 'ACTIONED', label: REPORT_STATUS_LABELS.ACTIONED },
  ];

  protected readonly reports = signal<Report[]>([]);
  protected readonly emailsById = signal<Record<string, string>>({});
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly filter = signal<ReportFilter>('ALL');
  protected readonly updatingId = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  /** Open reports first (the queue), then newest first. */
  protected readonly visibleReports = computed(() => {
    const filter = this.filter();
    return this.reports()
      .filter((report) => filter === 'ALL' || report.status === filter)
      .sort((a, b) => {
        if (a.status !== b.status) {
          return a.status === 'OPEN' ? -1 : b.status === 'OPEN' ? 1 : 0;
        }
        return b.createdAt.localeCompare(a.createdAt);
      });
  });

  protected readonly openCount = computed(
    () => this.reports().filter((report) => report.status === 'OPEN').length,
  );

  ngOnInit(): void {
    this.load();
  }

  protected countFor(filter: ReportFilter): number {
    return filter === 'ALL'
      ? this.reports().length
      : this.reports().filter((report) => report.status === filter).length;
  }

  protected statusLabel(status: ReportStatus): string {
    return REPORT_STATUS_LABELS[status] ?? status;
  }

  protected statusTone(status: ReportStatus): BadgeTone {
    return REPORT_STATUS_TONES[status] ?? 'neutral';
  }

  /** Email of a user when known, otherwise the raw id. */
  protected who(userId: string): string {
    return this.emailsById()[userId] ?? userId;
  }

  protected decide(report: Report, decision: ReportDecision): void {
    this.updatingId.set(report.id);
    this.actionError.set(null);

    this.reportService.updateStatus(report.id, decision).subscribe({
      next: (updated) => {
        this.updatingId.set(null);
        this.reports.update((reports) => reports.map((r) => (r.id === updated.id ? updated : r)));
      },
      error: (err: unknown) => {
        this.updatingId.set(null);
        if (err instanceof HttpErrorResponse && err.status === 409) {
          this.actionError.set('Ce signalement a déjà été traité : la liste est actualisée.');
          this.load();
        } else {
          this.actionError.set(extractErrorMessage(err, 'Impossible de mettre à jour ce signalement.'));
        }
      },
    });
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    forkJoin({
      reports: this.reportService.list(),
      // Email resolution is a nicety: a failure must not hide the queue.
      users: this.userService.list().pipe(catchError(() => of<User[]>([]))),
    }).subscribe({
      next: ({ reports, users }) => {
        this.reports.set(reports);
        this.emailsById.set(Object.fromEntries(users.map((user) => [user.id, user.email])));
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger les signalements.'));
        this.loading.set(false);
      },
    });
  }
}
