import { Component, computed, input, output } from '@angular/core';

import { ButtonComponent } from '../button/button.component';

/**
 * Prev/next pager for a backend `Page<T>` (see `shared/pagination.ts`):
 * purely presentational, like the rest of `shared/ui/` — the consumer owns
 * the current page signal and re-fetches on `pageChange`. `page` is 0-based,
 * shown to the user as 1-based ("Page 2 sur 5").
 */
@Component({
  selector: 'app-paginator',
  imports: [ButtonComponent],
  templateUrl: './paginator.component.html',
})
export class PaginatorComponent {
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly totalElements = input.required<number>();
  readonly disabled = input(false);

  readonly pageChange = output<number>();

  protected readonly hasPrevious = computed(() => this.page() > 0);
  protected readonly hasNext = computed(() => this.page() + 1 < this.totalPages());

  protected previous(): void {
    if (this.hasPrevious()) {
      this.pageChange.emit(this.page() - 1);
    }
  }

  protected next(): void {
    if (this.hasNext()) {
      this.pageChange.emit(this.page() + 1);
    }
  }
}
