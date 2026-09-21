import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { TravelRowsTableComponent } from './travel-rows-table.component';
import { TravelRow } from './stats.service';

describe('TravelRowsTableComponent', () => {
  const row: TravelRow = {
    destinationId: 'd1', managerId: 'm1', name: 'Lisbon', country: 'Portugal', startDate: '2026-05-01', endDate: '2026-05-08',
    status: 'PAST', capacity: 20, subscribers: 15, feedbackCount: 4, averageRating: 4.5, dampedRating: 4.1,
    income: { EUR: 1200 }, incomeAmount: 1200,
  };

  function render(rows: TravelRow[], inputs: Record<string, unknown> = {}): HTMLElement {
    const fixture = TestBed.createComponent(TravelRowsTableComponent);
    fixture.componentRef.setInput('rows', rows);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  beforeEach(() =>
    TestBed.configureTestingModule({ imports: [TravelRowsTableComponent], providers: [provideRouter([])] }),
  );

  it('shows the status in French, subscribers over capacity, rating and income', () => {
    const el = render([row]);
    const text = el.textContent!.replace(/[\s  ]+/g, ' ');

    expect(text).toContain('Terminé');
    expect(text).toContain('15 / 20');
    expect(text).toContain('4,5 sur 5');
    expect(text).toContain('1 200,00 €');
  });

  it('shows "indisponible", never 0, when the income is null', () => {
    const el = render([{ ...row, income: null, incomeAmount: null }]);

    expect(el.querySelector('[data-testid="row-income"]')!.textContent!.trim()).toBe('indisponible');
  });

  it('omits the capacity when unlimited and the feedback column unless a link base is given', () => {
    const plain = render([{ ...row, capacity: null }]);
    expect(plain.textContent).not.toContain('15 /');
    expect(plain.textContent).not.toContain('Voir les avis');

    const linked = render([row], { feedbackLink: '/manager/travels' });
    expect(linked.querySelector('a[href="/manager/travels/d1/feedback"]')).not.toBeNull();
  });

  it('shows the empty message and escapes names', () => {
    expect(render([], { emptyMessage: 'Rien.' }).textContent).toContain('Rien.');

    const el = render([{ ...row, name: '<img src=x onerror=alert(1)>' }]);
    expect(el.querySelector('img')).toBeNull();
  });
});
