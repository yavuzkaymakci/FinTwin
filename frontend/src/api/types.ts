/** Wire types of the backend API (see the Java DTO records; field names are identical). */

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Stats {
  mean: number;
  p5: number;
  p50: number;
  p95: number;
  p99?: number;
}

// ------------------------------------------------------------------ portfolio and customers

export interface CustomerListItem {
  id: number;
  segment: string;
  city: string;
  age: number;
  monthlyNetIncome: number;
  status: string;
  customerSince: string;
  pd: number | null;
  churn: number | null;
  riskGrade: number | null;
  scoredAt: string | null;
  utilizationRate: number | null;
  liquidityBufferMonths: number | null;
  totalDebt: number | null;
  liquidBalance: number | null;
}

export interface SegmentSummary {
  segment: string;
  customers: number;
  meanPd: number;
  meanChurn: number;
  expectedLoss: number;
  totalDebt: number;
  meanUtilization: number | null;
  meanLiquidityBufferMonths: number | null;
  highRiskCustomers: number;
}

export interface RiskyCustomer {
  customerId: number;
  segment: string;
  city: string;
  pd: number;
  churn: number;
  riskGrade: number;
  utilizationRate: number | null;
  totalDebt: number;
}

export interface PortfolioSummary {
  scoredAt: string | null;
  activeCustomers: number;
  scoredCustomers: number;
  meanPd: number;
  meanChurn: number;
  totalDebt: number;
  expectedLoss: number;
  lgd: number;
  highRiskCustomers: number;
  customersByGrade: Record<string, number>;
  segments: SegmentSummary[];
  topRiskCustomers: RiskyCustomer[];
}

export interface MacroIndicator {
  indicator: 'MONTHLY_INFLATION' | 'POLICY_RATE' | 'USD_TRY';
  period: string;
  value: number;
  source: string;
}

export interface CustomerDetail {
  customer: {
    id: number;
    age: number;
    city: string;
    occupationGroup: string;
    segment: string;
    monthlyNetIncome: number;
    customerSince: string;
    status: string;
  };
  accounts: { id: number; type: string; currency: string; balance: number; openedAt: string; closedAt: string | null;
    creditLimit: number | null }[];
  loans: { id: number; type: string; principal: number; termMonths: number; disbursedAt: string; status: string }[];
  events: { id: number; type: string; eventDate: string; details: string | null }[];
}

export interface Transaction {
  id: number;
  accountId: number;
  amount: number;
  currency: string;
  direction: 'DEBIT' | 'CREDIT';
  channel: string;
  installmentCount: number;
  rawDescription: string;
  merchantName: string | null;
  occurredAt: string;
}

// ------------------------------------------------------------------ twin

export interface TwinFeatures {
  evaluatedAt: string;
  checkingBalance: number;
  depositBalance: number;
  usdBalance: number;
  cardDebt: number;
  cardLimit: number;
  utilizationRate: number | null;
  loanOutstanding: number;
  totalDebt: number;
  activeLoans: number;
  spend30d: number;
  spend90d: number;
  spendByCategory30d: Record<string, number>;
  spendByCategory90d: Record<string, number>;
  spendTrend: number | null;
  incomeEstimate: number | null;
  incomeMonthsObserved: number;
  incomeCv: number | null;
  salaryDayDeviation: number | null;
  monthlyObligations: number;
  debtToIncome: number | null;
  latePaymentCount: number;
  daysSinceLastLatePayment: number | null;
  avgMonthlyExpense: number;
  liquidityBufferMonths: number | null;
}

export interface TwinView {
  customerId: number;
  asOf: string;
  source: 'CURRENT' | 'REPLAYED';
  lastSequence: number;
  lastEventAt: string;
  segment: string;
  city: string;
  age: number;
  customerSince: string;
  accounts: { accountId: number; type: string; currency: string; balance: number; creditLimit: number | null;
    closed: boolean }[];
  loans: { loanId: number; type: string; installment: number; outstanding: number; remainingInstallments: number;
    active: boolean }[];
  features: TwinFeatures;
}

export interface FeatureHistory {
  customerId: number;
  feature: string;
  points: { date: string; value: number | null; source: 'SNAPSHOT' | 'CURRENT' }[];
}

export interface RiskReason {
  code: string;
  feature: string;
  value: number | string | null;
  portfolioMean: number | null;
  impact: number;
  text: string;
  textTr: string;
}

export interface RiskScore {
  customerId: number;
  pd: number;
  churn: number;
  riskGrade: number;
  reasons: { pd: RiskReason[]; churn: RiskReason[] };
  pdModelVersion: string;
  churnModelVersion: string;
  scoredAt: string;
  featuresAsOf: string;
}

export interface Band {
  p10: number;
  p50: number;
  p90: number;
}

export interface ForecastMonth {
  month: string;
  balance: Band;
  liquidityBufferMonths: Band;
  cumulativeDefaultProbability: number;
}

export interface CustomerForecast {
  customerId: number;
  runId: number;
  scenarioCode: string;
  scenarioName: string;
  startDate: string | null;
  forecast: {
    startBalance: number;
    startLiquidityBufferMonths: number | null;
    pdStart: number;
    pdHorizon: number;
    defaultProbability: number;
    churnProbability: number;
    months: ForecastMonth[];
  };
}

// ------------------------------------------------------------------ scenarios and runs

export type ShockProfile = 'IMMEDIATE' | 'GRADUAL';

export interface SegmentWageRule {
  segment: string;
  wageIndexationPct: number;
  wageAdjustmentLagMonths: number;
}

export interface ScenarioParameters {
  horizonMonths: number;
  usdTryChangePct: number;
  policyRateChangeBp: number;
  monthlyInflationPct: number[];
  wageIndexationPct: number;
  wageAdjustmentLagMonths: number;
  segmentWageRules: SegmentWageRule[];
  shockProfile: ShockProfile;
  shockRampMonths: number;
  monteCarloPaths: number;
  seed: number;
}

export interface Scenario {
  id: number;
  code: string;
  name: string;
  description: string;
  parameters: ScenarioParameters;
  preset: boolean;
  createdAt: string;
  updatedAt: string;
}

export type RunStatus = 'PENDING' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface MonthlyResult {
  month: string;
  defaultRate: Stats;
  churnRate: Stats;
  cumulativeDefaultRate: Stats;
  cumulativeChurnRate: Stats;
  meanPd: number | null;
}

export interface PortfolioResult {
  customers: number;
  creditCustomers: number;
  eadStart: number;
  meanPdStart: number;
  meanChurnStart: number;
  defaultRate: Stats;
  churnRate: Stats;
  loss: Stats;
  expectedLoss: number;
  lossP99: number;
  unexpectedLossP99: number;
  expectedLossPctOfEad: number;
  monthly: MonthlyResult[];
}

export interface SegmentResult {
  segment: string;
  customers: number;
  creditCustomers: number;
  eadStart: number;
  meanPdStart: number;
  defaultRate: Stats;
  churnRate: Stats;
  expectedLoss: number;
  lossP99: number;
}

export interface TopCustomer {
  customerId: number;
  segment: string;
  pdStart: number;
  pdHorizon: number;
  pdIncrease: number;
  defaultProbability: number;
  reasons: { feature: string; impact: number; textTr: string }[];
}

export interface RunResult {
  startDate: string;
  horizonMonths: number;
  paths: number;
  modelVersions: { pd: string; churn: string };
  scenario: ScenarioParameters;
  macroPath: { month: string; inflationPct: number; usdTry: number; policyRatePct: number }[];
  portfolio: PortfolioResult;
  segments: SegmentResult[];
  migration: { from: number[]; to: string[]; counts: number[][]; rates: number[][] };
  topCustomers: TopCustomer[];
  baseline: { scenario: ScenarioParameters; portfolio: PortfolioResult; segments: SegmentResult[] } | null;
  deltaVsBaseline: {
    defaultRate: Stats;
    churnRate: Stats;
    loss: Stats;
    segments: { segment: string; defaultRate: Stats; churnRate: Stats; expectedLoss: number }[];
  } | null;
  durationSeconds: number;
}

export interface SimulationRun {
  id: number;
  scenarioId: number;
  scenarioCode: string;
  scenarioName: string;
  baselineScenarioId: number | null;
  startDate: string | null;
  status: RunStatus;
  progress: number;
  requestedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
  durationSeconds: number | null;
  peakRssMb: number | null;
  result: RunResult | null;
}

export interface RunComparison {
  runs: {
    runId: number;
    scenarioCode: string;
    scenarioName: string;
    startDate: string;
    horizonMonths: number;
    paths: number;
    defaultRate: Record<string, number>;
    churnRate: Record<string, number>;
    expectedLoss: number;
    lossP99: number;
    expectedLossPctOfEad: number;
    segmentDefaultRate: Record<string, number>;
    defaultRateDeltaVsFirst: number;
    expectedLossDeltaVsFirst: number;
  }[];
}

// ------------------------------------------------------------------ assistants

export interface ToolCall {
  name: string;
  arguments: string;
  result: string | null;
  ok: boolean;
  durationMs: number;
}

export interface ChatAnswer {
  conversationId: string;
  answer: string;
  status: 'OK' | 'REFUSED' | 'TOOL_LIMIT';
  toolCalls: ToolCall[];
  runIds: number[];
  validation: { passed: boolean; checked: number; unverified: string[] } | null;
  usage: { promptTokens: number | null; completionTokens: number | null; totalTokens: number | null } | null;
  durationMs: number;
  model: string | null;
}

export interface ConversationSummary {
  id: string;
  title: string | null;
  turns: number;
  createdAt: string;
  lastMessageAt: string;
}

export interface ConversationTurn {
  createdAt: string;
  question: string;
  answer: string | null;
  status: string;
  toolCalls: ToolCall[] | null;
  runIds: number[];
  validation: { passed: boolean; checked: number; unverified: string[] } | null;
  durationMs: number | null;
  totalTokens: number | null;
}

// ------------------------------------------------------------------ system and live feed

export interface SystemStatus {
  time: string;
  ml: {
    reachable: boolean;
    circuitBreaker: string;
    error: string | null;
    models: { name: string; version: string; trainedAt: string | null; summary: Record<string, unknown> }[];
  };
  ai: { provider: string; model: string | null; demo: boolean; configured: boolean };
  replay: { mode: string; simulatedTime: string | null; secondsPerDay: number | null };
}

export interface ReplayStatus {
  mode: string;
  datasetEpoch: string | null;
  feedStart: string | null;
  feedEnd: string | null;
  simulatedTime: string | null;
  secondsPerDay: number | null;
  position: number;
  totalEvents: number;
  publishedTotal: number;
  sessionPublished: number;
  eventsPerSecond: number;
  sessionStartedAt: string | null;
  lastError: string | null;
}

export interface TwinLiveView {
  storedEvents: number;
  countedAt: string;
  received: number;
  processed: number;
  duplicates: number;
  deadLettered: number;
  recent: {
    customerId: number;
    customerSequence: number;
    eventType: string;
    occurredAt: string;
    recordedAt: string;
    amount: string | null;
    currency: string | null;
    direction: string | null;
    text: string | null;
  }[];
}
