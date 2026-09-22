import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { FeedbackListComponent } from './feedback-list.component';
import { Feedback } from './feedback.service';

describe('FeedbackListComponent', () => {
  function feedback(overrides: Partial<Feedback> = {}): Feedback {
    return {
      id: 'f1',
      travelerId: 'traveler-1',
      destinationId: 'dest-1',
      destinationName: 'Lisbon',
      destinationCountry: 'Portugal',
      destinationEndDate: '2026-08-10',
      rating: 5,
      comment: 'Super voyage',
      createdAt: '2026-08-11T10:00:00Z',
      ...overrides,
    };
  }

  function render(items: Feedback[], inputs: Record<string, unknown> = {}): HTMLElement {
    const fixture = TestBed.createComponent(FeedbackListComponent);
    fixture.componentRef.setInput('items', items);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  beforeEach(() =>
    TestBed.configureTestingModule({ imports: [FeedbackListComponent], providers: [provideRouter([])] }),
  );

  it('renders the rating, the comment and a link to the travel', () => {
    const el = render([feedback()]);

    expect(el.textContent).toContain('5 sur 5');
    expect(el.textContent).toContain('Super voyage');
    expect(el.querySelector('a')!.getAttribute('href')).toBe('/travels/dest-1');
  });

  it('renders a hostile comment as literal text, never as HTML', () => {
    const el = render([feedback({ comment: '<img src=x onerror="alert(1)"><script>alert(2)</script>' })]);

    expect(el.querySelector('img')).toBeNull();
    expect(el.querySelector('script')).toBeNull();
    expect(el.querySelector('[data-testid="feedback-comment"]')!.textContent).toContain('<script>alert(2)</script>');
  });

  it('says there is no comment when the traveler left none', () => {
    const el = render([feedback({ comment: null })]);

    expect(el.textContent).toContain('Pas de commentaire.');
  });

  it('shows the author id only when asked (managers/admins), and the travel name only when asked', () => {
    const hidden = render([feedback()], { showDestination: false });
    expect(hidden.textContent).not.toContain('traveler-1');
    expect(hidden.querySelector('a')).toBeNull();

    const shown = render([feedback()], { showAuthor: true });
    expect(shown.textContent).toContain('traveler-1');
  });

  it('shows the empty message', () => {
    const el = render([], { emptyMessage: 'Rien du tout.' });

    expect(el.querySelector('[data-testid="feedback-empty"]')!.textContent).toContain('Rien du tout.');
  });

  describe('pagination (client-side — the backend does not paginate this list)', () => {
    function items(count: number): Feedback[] {
      return Array.from({ length: count }, (_, i) => feedback({ id: `f${i}`, comment: `avis ${i}` }));
    }

    function comments(el: HTMLElement): string[] {
      return Array.from(el.querySelectorAll('[data-testid="feedback-comment"]')).map(
        (c) => c.textContent?.trim() ?? '',
      );
    }

    function button(el: HTMLElement, label: string): HTMLButtonElement {
      return Array.from(el.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
        (b) => b.textContent?.trim() === label,
      )!;
    }

    it('shows no pagination controls when everything fits on one page', () => {
      const el = render(items(3), { pageSize: 10 });

      expect(comments(el)).toHaveLength(3);
      expect(el.querySelector('[data-testid="feedback-pagination"]')).toBeNull();
    });

    it('shows only pageSize items per page, with Précédent disabled on the first page', () => {
      const el = render(items(25), { pageSize: 10 });

      expect(comments(el)).toEqual(Array.from({ length: 10 }, (_, i) => `avis ${i}`));
      expect(el.querySelector('[data-testid="feedback-page-info"]')!.textContent).toContain('Page 1 sur 3');
      expect(button(el, 'Précédent').disabled).toBe(true);
      expect(button(el, 'Suivant').disabled).toBe(false);
    });

    it('moves through pages with Suivant/Précédent', () => {
      const fixture = TestBed.createComponent(FeedbackListComponent);
      fixture.componentRef.setInput('items', items(25));
      fixture.componentRef.setInput('pageSize', 10);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      button(el, 'Suivant').click();
      fixture.detectChanges();
      expect(comments(el)).toEqual(Array.from({ length: 10 }, (_, i) => `avis ${i + 10}`));
      expect(el.querySelector('[data-testid="feedback-page-info"]')!.textContent).toContain('Page 2 sur 3');

      button(el, 'Suivant').click();
      fixture.detectChanges();
      expect(comments(el)).toEqual(['avis 20', 'avis 21', 'avis 22', 'avis 23', 'avis 24']);
      expect(button(el, 'Suivant').disabled).toBe(true);

      button(el, 'Précédent').click();
      fixture.detectChanges();
      expect(el.querySelector('[data-testid="feedback-page-info"]')!.textContent).toContain('Page 2 sur 3');
    });

    it('clamps back to the last valid page when a filter shrinks the list', () => {
      const fixture = TestBed.createComponent(FeedbackListComponent);
      fixture.componentRef.setInput('items', items(25));
      fixture.componentRef.setInput('pageSize', 10);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      button(el, 'Suivant').click();
      button(el, 'Suivant').click();
      fixture.detectChanges();
      expect(el.querySelector('[data-testid="feedback-page-info"]')!.textContent).toContain('Page 3 sur 3');

      fixture.componentRef.setInput('items', items(5));
      fixture.detectChanges();

      expect(comments(el)).toHaveLength(5);
      expect(el.querySelector('[data-testid="feedback-pagination"]')).toBeNull();
    });
  });
});
