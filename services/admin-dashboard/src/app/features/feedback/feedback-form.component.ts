import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { extractErrorMessage } from '../../shared/http-error';
import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import {
  FEEDBACK_COMMENT_MAX_LENGTH,
  Feedback,
  FeedbackService,
} from './feedback.service';

/** Message for each refusal the backend can give to a feedback (see FeedbackController#give). */
export function feedbackErrorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 403) {
      return "Vous ne pouvez donner un avis que sur un voyage auquel vous avez participé (inscription active).";
    }
    if (err.status === 409) {
      return 'Avis refusé : ce voyage n’est pas encore terminé, ou vous avez déjà donné votre avis (un seul avis par voyage, non modifiable).';
    }
  }
  return extractErrorMessage(err, 'Impossible d’envoyer votre avis.');
}

/**
 * Rating (1..5) + optional comment for a travel the caller took part in
 * (POST /destinations/{id}/feedback). The parent decides *when* to show it
 * (ACTIVE participation on an ended travel, no feedback yet); the backend
 * re-checks all of that and its refusal is turned into an explicit message.
 * The comment is plain text (≤ 1000 characters) and is never rendered as HTML.
 */
@Component({
  selector: 'app-feedback-form',
  imports: [FormsModule, AlertComponent, ButtonComponent],
  template: `
    <form class="flex flex-col gap-3" (ngSubmit)="submit()" data-testid="feedback-form">
      <fieldset class="flex flex-col gap-1">
        <legend class="font-mono text-xs tracking-wide text-ink-dim uppercase">Votre note</legend>
        <div class="flex flex-wrap gap-3">
          @for (value of ratings; track value) {
            <label class="inline-flex items-center gap-1 text-sm text-ink">
              <input
                type="radio"
                name="rating"
                [value]="value"
                [ngModel]="rating()"
                (ngModelChange)="rating.set($event)"
                [attr.data-testid]="'rating-' + value"
              />
              {{ value }} <span aria-hidden="true">★</span>
            </label>
          }
        </div>
      </fieldset>

      <div class="flex flex-col gap-1">
        <label for="feedbackComment" class="font-mono text-xs tracking-wide text-ink-dim uppercase"
          >Commentaire (facultatif)</label
        >
        <textarea
          id="feedbackComment"
          name="feedbackComment"
          rows="3"
          [maxLength]="commentMax"
          [(ngModel)]="comment"
          class="border-0 border-b-2 border-line-strong bg-transparent px-1 py-1.5 font-mono text-sm text-ink focus:border-amber focus:outline-none"
        ></textarea>
        <span class="text-xs text-ink-dim"
          >Texte brut, {{ commentMax }} caractères au maximum. Votre avis ne pourra pas être modifié.</span
        >
      </div>

      <div>
        <app-button type="submit" [disabled]="saving() || rating() === null">
          {{ saving() ? 'Envoi…' : 'Envoyer mon avis' }}
        </app-button>
      </div>

      @if (error()) {
        <app-alert variant="error">{{ error() }}</app-alert>
      }
    </form>
  `,
})
export class FeedbackFormComponent {
  private readonly feedbackService = inject(FeedbackService);

  readonly destinationId = input.required<string>();
  /** Emitted with the created feedback. */
  readonly saved = output<Feedback>();

  protected readonly ratings = [1, 2, 3, 4, 5];
  protected readonly commentMax = FEEDBACK_COMMENT_MAX_LENGTH;
  protected readonly rating = signal<number | null>(null);
  protected comment = '';
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  submit(): void {
    const rating = this.rating();
    if (rating === null) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.feedbackService.give(this.destinationId(), rating, this.comment).subscribe({
      next: (feedback) => {
        this.saving.set(false);
        this.saved.emit(feedback);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set(feedbackErrorMessage(err));
      },
    });
  }
}
