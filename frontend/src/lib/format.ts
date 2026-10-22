/** tr-TR formatting of numbers, money, percentages and dates. Null-safe: missing values render as "–". */

const LOCALE = 'tr-TR';
const DASH = '–';

type Num = number | string | null | undefined;

function toNumber(value: Num): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

export function formatNumber(value: Num, digits = 0): string {
  const n = toNumber(value);
  return n === null
    ? DASH
    : n.toLocaleString(LOCALE, { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/** Money in TRY, e.g. "12.345 ₺"; {@code compact} shortens large amounts ("5,7 Mn ₺"). */
export function formatMoney(value: Num, options: { digits?: number; compact?: boolean } = {}): string {
  const n = toNumber(value);
  if (n === null) return DASH;
  if (options.compact && Math.abs(n) >= 1_000_000) {
    return `${(n / 1_000_000).toLocaleString(LOCALE, { maximumFractionDigits: 2 })} Mn ₺`;
  }
  if (options.compact && Math.abs(n) >= 10_000) {
    return `${(n / 1_000).toLocaleString(LOCALE, { maximumFractionDigits: 1 })} B ₺`;
  }
  return `${n.toLocaleString(LOCALE, { maximumFractionDigits: options.digits ?? 0,
    minimumFractionDigits: options.digits ?? 0 })} ₺`;
}

/** A fraction as a Turkish percentage: 0.0323 -> "%3,23". */
export function formatPercent(fraction: Num, digits = 2): string {
  const n = toNumber(fraction);
  return n === null ? DASH : `%${(n * 100).toLocaleString(LOCALE, { minimumFractionDigits: digits,
    maximumFractionDigits: digits })}`;
}

/** A difference of fractions in percentage points with a sign: 0.0098 -> "+0,98 puan". */
export function formatPoints(fraction: Num, digits = 2): string {
  const n = toNumber(fraction);
  if (n === null) return DASH;
  const sign = n > 0 ? '+' : n < 0 ? '−' : '±';
  return `${sign}${Math.abs(n * 100).toLocaleString(LOCALE, { minimumFractionDigits: digits,
    maximumFractionDigits: digits })} puan`;
}

export function formatDate(value: string | Date | null | undefined): string {
  if (!value) return DASH;
  const d = typeof value === 'string' ? new Date(value.length === 10 ? `${value}T00:00:00` : value) : value;
  return Number.isNaN(d.getTime()) ? DASH : d.toLocaleDateString(LOCALE, { day: '2-digit', month: 'short',
    year: 'numeric' });
}

export function formatDateTime(value: string | Date | null | undefined): string {
  if (!value) return DASH;
  const d = typeof value === 'string' ? new Date(value) : value;
  return Number.isNaN(d.getTime()) ? DASH : d.toLocaleString(LOCALE, { day: '2-digit', month: 'short',
    hour: '2-digit', minute: '2-digit' });
}

export function formatTime(value: string | Date | null | undefined): string {
  if (!value) return DASH;
  const d = typeof value === 'string' ? new Date(value) : value;
  return Number.isNaN(d.getTime()) ? DASH : d.toLocaleTimeString(LOCALE, { hour: '2-digit', minute: '2-digit',
    second: '2-digit' });
}

/** "2026-10" -> "Eki 2026". */
export function formatMonth(value: string | null | undefined): string {
  if (!value) return DASH;
  const d = new Date(`${value.slice(0, 7)}-01T00:00:00`);
  return Number.isNaN(d.getTime()) ? value : d.toLocaleDateString(LOCALE, { month: 'short', year: 'numeric' });
}
