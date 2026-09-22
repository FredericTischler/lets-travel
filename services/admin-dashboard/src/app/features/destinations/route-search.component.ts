import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { Destination, DestinationService } from './destination.service';
import {
  DEFAULT_ROUTE_HOPS,
  MAX_ROUTE_HOPS,
  MIN_ROUTE_HOPS,
  RouteSearchResult,
  RouteSearchService,
} from './route-search.service';

/**
 * Bonus screen (sujet §11 addendum, ADR §11): itinerary search across the
 * TRANSPORT graph, `GET /destinations/{fromId}/routes/{toId}?maxHops=`. Open
 * to the 3 roles (no ownership on a read across the whole graph), so this is
 * its own route rather than folded into the ADMIN-only `/destinations`
 * screen that owns TRANSPORT create/edit/delete.
 *
 * The origin/target selectors reuse the same destination list already
 * loaded by the catalogue and by the admin destinations screen
 * (`DestinationService.list()`), so no new backend endpoint is needed here.
 */
@Component({
  selector: 'app-route-search',
  imports: [FormsModule, AlertComponent, ButtonComponent, CardComponent, InputComponent],
  templateUrl: './route-search.component.html',
})
export class RouteSearchComponent implements OnInit {
  private readonly destinationService = inject(DestinationService);
  private readonly routeSearchService = inject(RouteSearchService);

  protected readonly minHops = MIN_ROUTE_HOPS;
  protected readonly maxHops = MAX_ROUTE_HOPS;
  protected readonly defaultHops = DEFAULT_ROUTE_HOPS;

  protected readonly destinations = signal<Destination[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected fromId = '';
  protected toId = '';
  protected maxHopsInput: number | null = DEFAULT_ROUTE_HOPS;

  protected readonly searching = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly result = signal<RouteSearchResult | null>(null);

  ngOnInit(): void {
    this.destinationService.list().subscribe({
      next: (destinations) => {
        this.destinations.set(destinations);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Impossible de charger la liste des destinations.');
        this.loading.set(false);
      },
    });
  }

  search(): void {
    if (!this.fromId || !this.toId) {
      return;
    }
    if (this.fromId === this.toId) {
      this.searchError.set('Choisissez deux destinations différentes.');
      return;
    }

    const maxHops =
      this.maxHopsInput === null || this.maxHopsInput === this.defaultHops
        ? undefined
        : this.maxHopsInput;

    this.searching.set(true);
    this.searchError.set(null);
    this.result.set(null);

    this.routeSearchService.findRoute(this.fromId, this.toId, maxHops).subscribe({
      next: (result) => {
        this.searching.set(false);
        this.result.set(result);
      },
      error: (err: unknown) => {
        this.searching.set(false);
        this.searchError.set(extractErrorMessage(err, "Impossible de rechercher un itinéraire."));
      },
    });
  }
}
