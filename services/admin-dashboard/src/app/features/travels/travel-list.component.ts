import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, catchError, debounceTime, map, of, switchMap } from 'rxjs';

import { extractErrorMessage } from '../../shared/http-error';
import { TranslatePipe } from '../../shared/i18n/translate.pipe';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { LoadingComponent } from '../../shared/ui/loading/loading.component';
import { ItinerarySuggestionsComponent } from '../itineraries/itinerary-suggestions.component';
import { RecommendationsComponent } from '../recommendations/recommendations.component';
import {
  AutocompleteSuggestion,
  Destination,
  DestinationService,
} from '../destinations/destination.service';

/** Debounce applied to the autocomplete calls while the user types. */
export const AUTOCOMPLETE_DEBOUNCE_MS = 300;
/** Below this many characters no autocomplete call is made. */
export const AUTOCOMPLETE_MIN_CHARS = 2;

const SEARCH_UNAVAILABLE_NOTICE =
  'La recherche est momentanément indisponible : la liste complète des voyages est affichée.';

/**
 * Traveler catalogue: the list of every destination (GET /destinations) with
 * an Elasticsearch-backed search box.
 *
 * - Typing calls GET /destinations/autocomplete after a debounce, `switchMap`
 *   dropping any answer that arrives for a stale prefix.
 * - Submitting calls GET /destinations/search?q=... (full-text across name,
 *   country, activities, accommodations); an empty query goes back to the
 *   plain list.
 * - Elasticsearch is an optional stack profile: a 503 from either endpoint is
 *   not an error for the user — the screen falls back to the plain list and
 *   shows a notice, so browsing and subscribing keep working.
 */
@Component({
  selector: 'app-travel-list',
  imports: [
    FormsModule,
    RouterLink,
    DecimalPipe,
    AlertComponent,
    ButtonComponent,
    CardComponent,
    ItinerarySuggestionsComponent,
    LoadingComponent,
    RecommendationsComponent,
    TranslatePipe,
  ],
  templateUrl: './travel-list.component.html',
})
export class TravelListComponent implements OnInit {
  private readonly destinationService = inject(DestinationService);
  private readonly router = inject(Router);

  protected readonly travels = signal<Destination[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Set when a search/autocomplete call answered 503 (Elasticsearch down). */
  protected readonly notice = signal<string | null>(null);

  protected readonly query = signal('');
  /** The query the displayed results correspond to, `null` for the plain list. */
  protected readonly activeSearch = signal<string | null>(null);

  protected readonly suggestions = signal<AutocompleteSuggestion[]>([]);
  protected readonly activeSuggestion = signal(-1);

  private readonly typed$ = new Subject<string>();

  constructor() {
    this.typed$
      .pipe(
        // No distinctUntilChanged() here: debounceTime already coalesces rapid
        // keystrokes down to the last value in each quiet window, so
        // distinctUntilChanged would only ever compare *emitted* (post-debounce)
        // values — it can't see whatever was typed in between two debounce
        // windows. That silently drops a legitimate re-search: type "ab", type
        // more, clear, retype "ab" — the second "ab" never reaches here as
        // "distinct" from the first, because everything typed in between was
        // coalesced away before distinctUntilChanged ever saw it.
        debounceTime(AUTOCOMPLETE_DEBOUNCE_MS),
        map((value) => value.trim()),
        switchMap((prefix) => {
          if (prefix.length < AUTOCOMPLETE_MIN_CHARS) {
            return of<AutocompleteSuggestion[]>([]);
          }
          return this.destinationService.autocomplete(prefix).pipe(
            catchError((err: unknown) => {
              this.handleSearchFailure(err);
              return of<AutocompleteSuggestion[]>([]);
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((suggestions) => {
        this.suggestions.set(suggestions);
        this.activeSuggestion.set(-1);
      });
  }

  ngOnInit(): void {
    this.loadAll();
  }

  protected onQueryChange(value: string): void {
    this.query.set(value);
    this.typed$.next(value);
    if (value.trim().length < AUTOCOMPLETE_MIN_CHARS) {
      this.suggestions.set([]);
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.suggestions().length;
    if (count === 0) {
      return;
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.activeSuggestion.update((index) => (index + 1) % count);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.activeSuggestion.update((index) => (index <= 0 ? count - 1 : index - 1));
        break;
      case 'Escape':
        this.suggestions.set([]);
        break;
      case 'Enter': {
        const chosen = this.suggestions()[this.activeSuggestion()];
        if (chosen) {
          event.preventDefault();
          this.openSuggestion(chosen);
        }
        break;
      }
    }
  }

  protected openSuggestion(suggestion: AutocompleteSuggestion): void {
    this.suggestions.set([]);
    this.router.navigate(['/travels', suggestion.id]);
  }

  /** Runs the full-text search for the current query (or resets to the list when empty). */
  protected search(): void {
    const q = this.query().trim();
    this.suggestions.set([]);
    if (q === '') {
      this.clear();
      return;
    }

    this.loading.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.destinationService.search(q).subscribe({
      next: (results) => {
        this.travels.set(results);
        this.activeSearch.set(q);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        // 503 -> plain list + notice; anything else is a real error.
        if (this.handleSearchFailure(err)) {
          this.activeSearch.set(null);
          this.loadAll(true);
        } else {
          this.error.set(extractErrorMessage(err, 'La recherche a échoué.'));
          this.loading.set(false);
        }
      },
    });
  }

  protected clear(): void {
    this.query.set('');
    this.typed$.next('');
    this.suggestions.set([]);
    this.activeSearch.set(null);
    this.loadAll();
  }

  /** Records the "search unavailable" notice on a 503; returns whether it was one. */
  private handleSearchFailure(err: unknown): boolean {
    if (err instanceof HttpErrorResponse && err.status === 503) {
      this.notice.set(SEARCH_UNAVAILABLE_NOTICE);
      return true;
    }
    return false;
  }

  private loadAll(keepNotice = false): void {
    this.loading.set(true);
    this.error.set(null);
    if (!keepNotice) {
      this.notice.set(null);
    }
    this.destinationService.list().subscribe({
      next: (travels) => {
        this.travels.set(travels);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(extractErrorMessage(err, 'Impossible de charger les voyages.'));
        this.loading.set(false);
      },
    });
  }
}
