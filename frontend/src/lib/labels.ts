/** Turkish UI labels for backend codes. */

export const SEGMENTS = ['MIN_WAGE', 'RETIREE', 'SELF_EMPLOYED', 'STUDENT', 'WHITE_COLLAR'] as const;
export type Segment = (typeof SEGMENTS)[number];

const SEGMENT_LABELS: Record<string, string> = {
  MIN_WAGE: 'Asgari ücretli',
  RETIREE: 'Emekli',
  SELF_EMPLOYED: 'Serbest meslek',
  STUDENT: 'Öğrenci',
  WHITE_COLLAR: 'Beyaz yaka',
};

export function segmentLabel(code: string | null | undefined): string {
  return code ? SEGMENT_LABELS[code] ?? code : '–';
}

const CATEGORY_LABELS: Record<string, string> = {
  GROCERY: 'Market',
  RESTAURANT: 'Restoran',
  ENTERTAINMENT: 'Eğlence',
  TRAVEL: 'Seyahat',
  CLOTHING: 'Giyim',
  ELECTRONICS: 'Elektronik',
  ECOMMERCE: 'E-ticaret',
  UTILITIES: 'Faturalar',
  TELECOM: 'İletişim',
  RENT: 'Kira',
  HEALTH: 'Sağlık',
  TRANSPORT: 'Ulaşım',
  FUEL: 'Akaryakıt',
  EDUCATION: 'Eğitim',
  CASH_WITHDRAWAL: 'Nakit çekim',
  OTHER: 'Diğer',
};

export function categoryLabel(code: string): string {
  return CATEGORY_LABELS[code] ?? code;
}

export const CUTTABLE_CATEGORIES = ['RESTAURANT', 'ENTERTAINMENT', 'TRAVEL', 'CLOTHING', 'ELECTRONICS', 'ECOMMERCE',
  'GROCERY', 'UTILITIES', 'TELECOM', 'TRANSPORT', 'FUEL'] as const;

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'Aktif', CHURNED: 'Ayrıldı', DEFAULTED: 'Temerrüt',
  PENDING: 'Bekliyor', QUEUED: 'Sırada', RUNNING: 'Çalışıyor', SUCCEEDED: 'Tamamlandı', FAILED: 'Başarısız',
  IDLE: 'Boşta', BULK: 'Toplu yayın', LIVE: 'Canlı', PREPARING: 'Hazırlanıyor',
  OK: 'Tamam', REFUSED: 'Reddedildi', TOOL_LIMIT: 'Araç sınırı',
};

export function statusLabel(code: string | null | undefined): string {
  return code ? STATUS_LABELS[code] ?? code : '–';
}

const ACCOUNT_LABELS: Record<string, string> = {
  CHECKING: 'Vadesiz hesap', TIME_DEPOSIT: 'Vadeli mevduat', CREDIT_CARD: 'Kredi kartı',
};
export function accountLabel(type: string, currency?: string): string {
  const base = ACCOUNT_LABELS[type] ?? type;
  return currency && currency !== 'TRY' ? `${base} (${currency})` : base;
}

const LOAN_LABELS: Record<string, string> = { PERSONAL: 'İhtiyaç kredisi', MORTGAGE: 'Konut kredisi' };
export function loanLabel(type: string): string {
  return LOAN_LABELS[type] ?? type;
}

const EVENT_LABELS: Record<string, string> = {
  TransactionRecorded: 'İşlem',
  CardStatementIssued: 'Kart ekstresi',
  LoanInstallmentSettled: 'Kredi taksiti',
  LoanDisbursed: 'Kredi kullandırımı',
  PaymentPastDue: 'Gecikmiş ödeme',
  AccountOpened: 'Hesap açılışı',
  AccountClosed: 'Hesap kapanışı',
  CustomerRegistered: 'Müşteri kaydı',
};
export function eventLabel(type: string): string {
  return EVENT_LABELS[type] ?? type;
}

const TOOL_LABELS: Record<string, string> = {
  listScenarios: 'Senaryoları listeledi',
  createScenario: 'Senaryo oluşturdu',
  startSimulation: 'Simülasyon başlattı',
  getRunStatus: 'Koşu durumunu sorguladı',
  waitForRun: 'Koşuyu bekledi',
  getRunResults: 'Sonuçları getirdi',
  compareRuns: 'Koşuları karşılaştırdı',
  getSegmentImpact: 'Segment etkisine baktı',
  getTopAffectedCustomers: 'Etkilenen müşterileri getirdi',
  getPortfolioRiskSummary: 'Portföy riskini özetledi',
  getMyTwinSummary: 'İkiz özetine baktı',
  getMySpendingBreakdown: 'Harcamaları inceledi',
  getMyCashFlowForecast: 'Nakit akışı tahminine baktı',
  simulateSavingsGoal: 'Hedef simülasyonu yaptı',
};
export function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name;
}

/** Twin feature names (camelCase) offered in the feature-history chart. */
export const HISTORY_FEATURES: { key: string; label: string; kind: 'pct' | 'months' | 'money' }[] = [
  { key: 'utilizationRate', label: 'Kart limit kullanımı', kind: 'pct' },
  { key: 'liquidityBufferMonths', label: 'Likidite tamponu (ay)', kind: 'months' },
  { key: 'debtToIncome', label: 'Borç ödemesi / gelir', kind: 'pct' },
  { key: 'checkingBalance', label: 'Vadesiz bakiye', kind: 'money' },
  { key: 'cardDebt', label: 'Kart borcu', kind: 'money' },
  { key: 'spend30d', label: '30 günlük harcama', kind: 'money' },
  { key: 'incomeEstimate', label: 'Tahmini aylık gelir', kind: 'money' },
];
