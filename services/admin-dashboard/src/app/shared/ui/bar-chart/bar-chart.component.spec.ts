import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BarChartComponent, BarChartPoint, niceMax } from './bar-chart.component';

describe('niceMax()', () => {
  it.each([
    [0, 1],
    [-5, 1],
    [Number.NaN, 1],
    [1, 1],
    [137, 200],
    [1137, 2000],
    [2300, 2500],
    [4100, 5000],
    [9000, 10000],
  ])('rounds %s up to %s', (input, expected) => {
    expect(niceMax(input)).toBe(expected);
  });
});

describe('BarChartComponent', () => {
  let fixture: ComponentFixture<BarChartComponent>;

  const points: BarChartPoint[] = [
    { label: 'juil. 2026', value: 0 },
    { label: 'août 2026', value: 500, detail: 'Autres devises : 30 $US' },
    { label: 'sept. 2026', value: 1200 },
  ];

  function render(data: BarChartPoint[]) {
    fixture = TestBed.createComponent(BarChartComponent);
    fixture.componentRef.setInput('title', 'Revenus par mois');
    fixture.componentRef.setInput('data', data);
    fixture.componentRef.setInput('format', (v: number) => `${v} €`);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => TestBed.configureTestingModule({ imports: [BarChartComponent] }));

  it('renders one focusable column per point with an accessible label', () => {
    render(points);

    const columns = el().querySelectorAll('[data-testid="chart-column"]');
    expect(columns.length).toBe(3);
    expect(columns[2].getAttribute('aria-label')).toBe('sept. 2026 : 1200 €');
    expect(columns[1].getAttribute('aria-label')).toContain('Autres devises : 30 $US');
    expect(columns[0].getAttribute('tabindex')).toBe('0');
  });

  it('scales the columns against a round axis maximum, a zero value having no bar', () => {
    render(points);

    const bars = Array.from(
      el().querySelectorAll('[data-testid="chart-column"] > span:last-child') as NodeListOf<HTMLElement>,
    );
    // max 1200 -> axis 2000: 1200 = 60 %, 500 = 25 %, 0 = 0 %.
    expect(bars.map((b) => b.style.height)).toEqual(['0%', '25%', '60%']);
  });

  it('shows the tooltip on focus and hides it on blur', () => {
    render(points);
    const column = el().querySelectorAll('[data-testid="chart-column"]')[1] as HTMLElement;

    column.dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    const tooltip = el().querySelector('[data-testid="chart-tooltip"]')!;
    expect(tooltip.textContent).toContain('500 €');
    expect(tooltip.textContent).toContain('août 2026');
    expect(tooltip.textContent).toContain('Autres devises : 30 $US');

    column.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="chart-tooltip"]')).toBeNull();
  });

  it('gives the values in a table twin, so nothing is only reachable by hovering', () => {
    render(points);

    const rows = Array.from(el().querySelectorAll('details tbody tr')).map((r) =>
      Array.from(r.querySelectorAll('td')).map((c) => c.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['juil. 2026', '0 €', '—'],
      ['août 2026', '500 €', 'Autres devises : 30 $US'],
      ['sept. 2026', '1200 €', '—'],
    ]);
  });

  it('says so, and draws no columns, when every value is zero', () => {
    render([{ label: 'sept. 2026', value: 0 }]);

    expect(el().querySelector('[data-testid="chart-empty"]')!.textContent).toContain(
      'Aucune donnée sur la période.',
    );
    expect(el().querySelector('[data-testid="chart-column"]')).toBeNull();
  });

  it('prints only every second x label when there are many columns', () => {
    render(Array.from({ length: 24 }, (_, i) => ({ label: `m${i}`, value: i + 1 })));

    const labels = Array.from(el().querySelectorAll('figure > div ul[aria-hidden="true"] li')).map((li) =>
      li.textContent?.trim(),
    );
    expect(labels.filter((l) => l !== '').length).toBe(12);
  });

  it('escapes labels instead of interpreting them as HTML', () => {
    render([{ label: '<img src=x onerror=alert(1)>', value: 10 }]);

    expect(el().querySelector('img')).toBeNull();
    expect(el().textContent).toContain('<img src=x onerror=alert(1)>');
  });
});
