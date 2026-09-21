import { formatMoney, formatMoneyMap, formatMonth, formatNumber, formatRating } from './format';

/** Intl uses narrow/non-breaking spaces: compare on a whitespace-normalised string. */
const norm = (text: string) => text.replace(/[\s  ]+/g, ' ');

describe('format helpers', () => {
  it('formats money in French with the currency symbol', () => {
    expect(norm(formatMoney(1234.5, 'EUR'))).toBe('1 234,50 €');
  });

  it('falls back to the ISO code when Intl does not know the currency', () => {
    expect(formatMoney(10, 'ZZZZ')).toBe('10.00 ZZZZ');
  });

  it('shows a dash for a missing or non-numeric amount, never 0', () => {
    expect(formatMoney(null, 'EUR')).toBe('—');
    expect(formatMoney(undefined, 'EUR')).toBe('—');
    expect(formatMoney('abc', 'EUR')).toBe('—');
  });

  it('lists every currency of a map side by side without summing them', () => {
    const text = norm(formatMoneyMap({ EUR: 100, USD: 30 }));
    expect(text).toContain('100,00 €');
    expect(text).toContain('30,00 $US');
    expect(text).toContain(' · ');
  });

  it('shows a dash for a null or empty money map', () => {
    expect(formatMoneyMap(null)).toBe('—');
    expect(formatMoneyMap({})).toBe('—');
  });

  it('formats a rating out of 5 and a dash when there is none', () => {
    expect(norm(formatRating(4.256))).toBe('4,26 / 5');
    expect(formatRating(null)).toBe('—');
  });

  it('formats a YYYY-MM month and leaves anything else untouched', () => {
    expect(norm(formatMonth('2026-09'))).toMatch(/^sept\.? 2026$/);
    expect(formatMonth('not-a-month')).toBe('not-a-month');
  });

  it('formats plain numbers with at most two decimals', () => {
    expect(formatNumber(42.5678)).toBe('42,57');
    expect(formatNumber(null)).toBe('—');
  });
});
