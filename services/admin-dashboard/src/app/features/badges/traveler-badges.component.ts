import { Component, OnInit, inject, signal } from '@angular/core';

import { TranslatePipe } from '../../shared/i18n/translate.pipe';
import { BADGE_LABELS, BadgeService, TravelerBadge } from './badge.service';

/**
 * "Vos badges" block (bonus feature, gamification,
 * docs/lets-travel-architecture-decisions.md §12): the three fixed tiers
 * (pays visités, avis donnés), earned or in progress. Failure is non-fatal —
 * the rest of "Mes abonnements" keeps working, only this block hides itself.
 */
@Component({
  selector: 'app-traveler-badges',
  imports: [TranslatePipe],
  templateUrl: './traveler-badges.component.html',
})
export class TravelerBadgesComponent implements OnInit {
  private readonly badgeService = inject(BadgeService);

  protected readonly badges = signal<TravelerBadge[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  protected readonly labels = BADGE_LABELS;

  ngOnInit(): void {
    this.badgeService.mine().subscribe({
      next: (data) => {
        this.badges.set(data.badges);
        this.loading.set(false);
      },
      // Non-fatal: badges are a bonus, never worth blocking the rest of the page over.
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }
}
