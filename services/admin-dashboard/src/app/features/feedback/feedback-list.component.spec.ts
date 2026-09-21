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
});
