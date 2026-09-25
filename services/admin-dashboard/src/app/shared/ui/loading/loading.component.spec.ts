import { TestBed } from '@angular/core/testing';

import { LoadingComponent } from './loading.component';

describe('LoadingComponent', () => {
  function render(inputs: Record<string, unknown> = {}): HTMLElement {
    const fixture = TestBed.createComponent(LoadingComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  beforeEach(() => TestBed.configureTestingModule({ imports: [LoadingComponent] }));

  it('defaults to the generic French label', () => {
    expect(render().textContent?.trim()).toBe('Chargement…');
  });

  it('shows a caller-provided label instead', () => {
    expect(render({ label: 'Loading…' }).textContent?.trim()).toBe('Loading…');
  });

  it('hides the spinner from screen readers', () => {
    const spinner = render().querySelector('.app-spinner');
    expect(spinner?.getAttribute('aria-hidden')).toBe('true');
  });
});
