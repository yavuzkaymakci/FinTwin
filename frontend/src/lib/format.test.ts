import { describe, expect, it } from 'vitest';
import { formatMoney, formatNumber, formatPercent, formatPoints } from './format';

describe('tr-TR formatting', () => {
  it('formats numbers and money with Turkish separators', () => {
    expect(formatNumber(1234567.891, 2)).toBe('1.234.567,89');
    expect(formatMoney(12345)).toBe('12.345 ₺');
    expect(formatMoney(5_700_000, { compact: true })).toBe('5,7 Mn ₺');
    expect(formatMoney(null)).toBe('–');
  });

  it('formats fractions as percentages and percentage points', () => {
    expect(formatPercent(0.0323)).toBe('%3,23');
    expect(formatPercent(0.5, 0)).toBe('%50');
    expect(formatPoints(0.0098)).toBe('+0,98 puan');
    expect(formatPoints(-0.01, 1)).toBe('−1,0 puan');
  });
});
