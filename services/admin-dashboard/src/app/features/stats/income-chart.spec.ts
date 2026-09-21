import { incomeChartPoints, incomeFormatter } from './income-chart';
import { IncomeSummary } from './stats.service';

const norm = (text: string) => text.replace(/[\s  ]+/g, ' ');

describe('income chart helpers', () => {
  const income: IncomeSummary = {
    referenceCurrency: 'EUR',
    totals: { EUR: 1700, USD: 30 },
    amount: 1700,
    months: 3,
    windowTotals: { EUR: 1700, USD: 30 },
    windowAmount: 1700,
    byMonth: [
      { month: '2026-07', totals: {}, amount: 0 },
      { month: '2026-08', totals: { EUR: 500, USD: 30 }, amount: 500 },
      { month: '2026-09', totals: { EUR: 1200 }, amount: 1200 },
    ],
  };

  it('plots the reference-currency amount, one point per month, months without income included', () => {
    const points = incomeChartPoints(income);

    expect(points.map((p) => p.value)).toEqual([0, 500, 1200]);
    expect(points).toHaveLength(3);
  });

  it('never folds another currency into the bar: it is spelled out in the detail', () => {
    const points = incomeChartPoints(income);

    expect(points[0].detail).toBeNull();
    expect(points[1].detail).toContain('Autres devises');
    expect(norm(points[1].detail!)).toContain('30,00 $US');
    expect(points[2].detail).toBeNull();
  });

  it('gives no point when income is unavailable', () => {
    expect(incomeChartPoints(null)).toEqual([]);
  });

  it('formats axis values in the reference currency without useless decimals', () => {
    const format = incomeFormatter(income);

    expect(norm(format(1000))).toBe('1 000 €');
    expect(norm(format(12.5))).toBe('12,50 €');
  });
});
