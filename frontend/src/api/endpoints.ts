/** Typed backend endpoints, grouped by area. */
import { api } from './client';
import type {
  ChatAnswer, ConversationSummary, ConversationTurn, CustomerDetail, CustomerForecast, CustomerListItem,
  FeatureHistory, MacroIndicator, Page, PortfolioSummary, ReplayStatus, RiskScore, RunComparison, Scenario,
  ScenarioParameters, SegmentSummary, SimulationRun, SystemStatus, Transaction, TwinLiveView, TwinView,
} from './types';

export interface CustomerQuery {
  search?: string;
  segment?: string;
  minGrade?: number;
  maxGrade?: number;
  sort?: string;
  direction?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export const portfolioApi = {
  summary: (top = 10) => api<PortfolioSummary>('/api/portfolio/summary', { query: { top } }),
  segments: () => api<SegmentSummary[]>('/api/portfolio/segments'),
  macro: (indicator: string) => api<MacroIndicator[]>('/api/macro-indicators', { query: { indicator } }),
};

export const customersApi = {
  list: (q: CustomerQuery) => api<Page<CustomerListItem>>('/api/customers', { query: { ...q } }),
  detail: (id: number) => api<CustomerDetail>(`/api/customers/${id}`),
  transactions: (id: number, from: string, to: string, size = 25) =>
    api<Page<Transaction>>(`/api/customers/${id}/transactions`, { query: { from, to, size } }),
};

export const twinsApi = {
  twin: (id: number, asOf?: string) => api<TwinView>(`/api/twins/${id}`, { query: { asOf } }),
  history: (id: number, feature: string) => api<FeatureHistory>(`/api/twins/${id}/history`, { query: { feature } }),
  risk: (id: number, refresh = false) => api<RiskScore>(`/api/twins/${id}/risk`, { query: { refresh } }),
  forecast: (id: number, runId?: number) => api<CustomerForecast>(`/api/twins/${id}/forecast`, { query: { runId } }),
};

export const scenariosApi = {
  list: () => api<Scenario[]>('/api/scenarios'),
  create: (body: { code: string; name: string; description?: string; parameters: ScenarioParameters }) =>
    api<Scenario>('/api/scenarios', { method: 'POST', body }),
  remove: (id: number) => api<void>(`/api/scenarios/${id}`, { method: 'DELETE' }),
  run: (id: number, body: { startDate?: string | null; baselineScenarioId?: number | null; topCustomers?: number }) =>
    api<SimulationRun>(`/api/scenarios/${id}/run`, { method: 'POST', body }),
  runs: (scenarioId?: number, limit = 50) => api<SimulationRun[]>('/api/simulation-runs', { query: { scenarioId, limit } }),
  runDetail: (id: number) => api<SimulationRun>(`/api/simulation-runs/${id}`),
  compare: (ids: number[]) => api<RunComparison>('/api/simulation-runs/compare', { query: { ids } }),
};

export type AssistantKind = 'scenario' | 'coach';

export const assistantApi = {
  chat: (kind: AssistantKind, message: string, conversationId?: string | null) =>
    api<ChatAnswer>(`/api/assistants/${kind}/chat`, { method: 'POST', body: { message, conversationId } }),
  conversations: (kind: AssistantKind) => api<ConversationSummary[]>(`/api/assistants/${kind}/conversations`),
  history: (kind: AssistantKind, id: string) =>
    api<ConversationTurn[]>(`/api/assistants/${kind}/conversations/${id}`),
};

export const systemApi = {
  status: () => api<SystemStatus>('/api/system/status'),
};

export const liveApi = {
  replay: () => api<ReplayStatus>('/api/admin/replay/status'),
  twin: (limit = 20) => api<TwinLiveView>('/api/live/twin', { query: { limit } }),
  startLive: (secondsPerDay: number) => api<ReplayStatus>('/api/admin/replay/live', { method: 'POST',
    query: { secondsPerDay } }),
  stop: () => api<ReplayStatus>('/api/admin/replay/stop', { method: 'POST' }),
  speed: (secondsPerDay: number) => api<ReplayStatus>('/api/admin/replay/speed', { method: 'POST',
    query: { secondsPerDay } }),
  prepareDemo: (days: number) => api<{ replayedUntil: string; liveFrom: string }>('/api/admin/replay/demo-prepare', {
    method: 'POST', query: { days } }),
};
