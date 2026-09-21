import { TestBed } from '@angular/core/testing';

import { StatTileComponent } from './stat-tile.component';

describe('StatTileComponent', () => {
  function render(inputs: Record<string, unknown>): HTMLElement {
    const fixture = TestBed.createComponent(StatTileComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  beforeEach(() => TestBed.configureTestingModule({ imports: [StatTileComponent] }));

  it('shows the label, the value and the hint', () => {
    const el = render({ label: 'Voyageurs', value: '12', hint: 'Inscrits actifs' });

    expect(el.textContent).toContain('Voyageurs');
    expect(el.querySelector('[data-testid="stat-value"]')!.textContent!.trim()).toBe('12');
    expect(el.querySelector('[data-testid="stat-hint"]')!.textContent).toContain('Inscrits actifs');
  });

  it('shows no hint line without a hint', () => {
    const el = render({ label: 'Voyageurs', value: '12' });

    expect(el.querySelector('[data-testid="stat-hint"]')).toBeNull();
  });

  it('interpolates its text (never HTML)', () => {
    const el = render({ label: '<b>x</b>', value: '<i>1</i>' });

    expect(el.querySelector('b')).toBeNull();
    expect(el.querySelector('i')).toBeNull();
  });
});
