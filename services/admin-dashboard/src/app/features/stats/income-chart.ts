import { formatMoney, formatMoneyMap, formatMonth } from '../../shared/format';
import { BarChartPoint } from '../../shared/ui/bar-chart/bar-chart.component';
import { IncomeSummary } from './stats.service';

/**
 * Columns of the "revenus par mois" chart: one per month of the window, the
 * height being the reference-currency amount (`amount`) — currencies are never
 * summed, so any *other* currency of that month is spelled out in the tooltip /
 * table `detail` instead of being folded into the bar.
 */
export function incomeChartPoints(income: IncomeSummary | null | undefined): BarChartPoint[] {
  if (!income) {
    return [];
  }
  return income.byMonth.map((entry) => {
    const others = Object.fromEntries(
      Object.entries(entry.totals).filter(([currency]) => currency !== income.referenceCurrency),
    );
    return {
      label: formatMonth(entry.month),
      value: Number(entry.amount) || 0,
      detail: Object.keys(others).length > 0 ? `Autres devises : ${formatMoneyMap(others)}` : null,
    };
  });
}

/** Axis / tooltip formatter of the income chart, in the reference currency. */
export function incomeFormatter(income: IncomeSummary | null | undefined): (value: number) => string {
  const currency = income?.referenceCurrency ?? 'EUR';
  return (value) => formatMoney(value, currency).replace(/,00(?=\s)/, '');
}
