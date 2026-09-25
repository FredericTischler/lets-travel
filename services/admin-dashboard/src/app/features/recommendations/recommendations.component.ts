import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { extractErrorMessage } from '../../shared/http-error';
import { formatNumber } from '../../shared/format';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { LoadingComponent } from '../../shared/ui/loading/loading.component';
import { Recommendation, RecommendationService } from './recommendation.service';

/**
 * "Suggestions pour vous" block of the traveler home: the destinations Neo4j
 * ranks highest for the caller, each with its score and — the point for the
 * audit ("precision of the recommendations") — the reasons behind it. Every
 * reason ends with its signed points, so the score is the sum of the listed
 * points and can be recomputed by hand; a negative reason means a trip similar
 * to one the traveler rated low.
 *
 * Failure is non-fatal: the catalogue below keeps working, only the block
 * shows a message (the recommendation call crosses Neo4j, the catalogue too,
 * but a scoring error must never hide the list).
 */
@Component({
  selector: 'app-recommendations',
  imports: [DecimalPipe, RouterLink, AlertComponent, CardComponent, LoadingComponent],
  templateUrl: './recommendations.component.html',
})
export class RecommendationsComponent implements OnInit {
  private readonly recommendationService = inject(RecommendationService);

  protected readonly items = signal<Recommendation[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly formatScore = formatNumber;

  ngOnInit(): void {
    this.recommendationService.mine().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger vos suggestions.'));
        this.loading.set(false);
      },
    });
  }
}
