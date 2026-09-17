import { Component, input } from '@angular/core';

/**
 * Container for a page section (a form, a list block). Purely visual —
 * groups projected content behind a bordered/rounded surface, with an
 * optional heading rendered above it so screens don't each hand-roll a
 * bare `<h2>` before their section markup.
 */
@Component({
  selector: 'app-card',
  templateUrl: './card.component.html',
})
export class CardComponent {
  readonly title = input<string | null>(null);
}