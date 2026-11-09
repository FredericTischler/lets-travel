import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { extractErrorMessage } from '../../shared/http-error';
import { TranslatePipe } from '../../shared/i18n/translate.pipe';
import { TranslateService } from '../../shared/i18n/translate.service';
import { formatNumber } from '../../shared/format';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { LoadingComponent } from '../../shared/ui/loading/loading.component';
import { ItineraryService, ItinerarySuggestion } from './itinerary.service';

/**
 * "Itinéraires suggérés" block (bonus feature,
 * docs/lets-travel-architecture-decisions.md §12): 2-to-3-stop chains built
 * from the existing multi-hop TRANSPORT pathfinding, each stop scored by the
 * same engine as "Suggestions pour vous" — the chain's score is just the sum
 * of its stops' scores.
 *
 * Failure is non-fatal: the catalogue below keeps working, only this block
 * shows a message.
 */
@Component({
  selector: 'app-itinerary-suggestions',
  imports: [RouterLink, AlertComponent, CardComponent, LoadingComponent, TranslatePipe],
  templateUrl: './itinerary-suggestions.component.html',
})
export class ItinerarySuggestionsComponent implements OnInit {
  private readonly itineraryService = inject(ItineraryService);
  private readonly translateService = inject(TranslateService);

  protected readonly items = signal<ItinerarySuggestion[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly formatScore = formatNumber;

  ngOnInit(): void {
    this.itineraryService.mine().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(
          extractErrorMessage(err, this.translateService.translate('Impossible de charger les itinéraires suggérés.')),
        );
        this.loading.set(false);
      },
    });
  }
}
