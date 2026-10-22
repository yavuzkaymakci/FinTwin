/** Pieces of an assistant message: tool tags, confirmation card, run links, validation warning, goal card. */
import { Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AlertTriangle, ArrowRight, Check, CircleSlash, Info, Target, Wrench } from 'lucide-react';
import type { ToolCall } from '@/api/types';
import { ProbabilityBar } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/primitives';
import { Popover, PopoverContent, PopoverTrigger, Tooltip } from '@/components/ui/radix';
import { formatMoney, formatNumber } from '@/lib/format';
import { categoryLabel, toolLabel } from '@/lib/labels';
import { cn } from '@/lib/utils';

export function Markdown({ text }: { text: string }) {
  return (
    <div className="prose-chat">
      <ReactMarkdown remarkPlugins={[remarkGfm]}
        components={{ a: ({ href, children }) => <a href={href} className="text-primary underline">{children}</a> }}>
        {text}
      </ReactMarkdown>
    </div>
  );
}

function parse(json: string | null | undefined): Record<string, unknown> | null {
  if (!json) return null;
  try {
    const v = JSON.parse(json) as unknown;
    return v && typeof v === 'object' ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function pretty(json: string | null | undefined): string {
  const v = parse(json);
  return v ? JSON.stringify(v, null, 2) : json ?? '';
}

/** A small chip per tool call; click shows the arguments (and a result excerpt). */
export function ToolTag({ call, pending }: { call: Pick<ToolCall, 'name'> & Partial<ToolCall>; pending?: boolean }) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button className={cn('inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium '
          + 'transition-colors hover:bg-muted', call.ok === false ? 'border-negative/40 text-negative'
          : 'text-muted-foreground')} aria-label={`${call.name} çağrıldı, parametreleri göster`}>
          <Wrench className={cn('size-3', pending && 'animate-spin')} />
          <span className="font-mono">{call.name}</span>
          <span className="hidden sm:inline">· {toolLabel(call.name)}</span>
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-[min(28rem,calc(100vw-2rem))]">
        <div className="mb-1 flex items-center justify-between text-xs">
          <span className="font-mono font-medium">{call.name}</span>
          {call.durationMs !== undefined && <span className="text-muted-foreground">{formatNumber(call.durationMs)} ms</span>}
        </div>
        <div className="text-[11px] font-medium text-muted-foreground">Parametreler</div>
        <pre className="mb-2 max-h-40 overflow-auto rounded bg-muted p-2 text-[11px]">{pending ? '…'
          : pretty(call.arguments) || '{}'}</pre>
        {call.result && (
          <>
            <div className="text-[11px] font-medium text-muted-foreground">Sonuç (kısaltılmış)</div>
            <pre className="max-h-48 overflow-auto rounded bg-muted p-2 text-[11px]">{pretty(call.result).slice(0, 1500)}</pre>
          </>
        )}
      </PopoverContent>
    </Popover>
  );
}

export interface PendingConfirmation {
  scenarioId: number;
  name: string;
  code: string;
  parameters: Record<string, unknown>;
}

/** The pending simulation of a message: a startSimulation that asked for confirmation and nothing started yet. */
export function pendingConfirmation(calls: ToolCall[] | null | undefined): PendingConfirmation | null {
  if (!calls?.length) return null;
  let pending: PendingConfirmation | null = null;
  for (const call of calls) {
    if (call.name !== 'startSimulation') continue;
    const result = parse(call.result);
    if (result?.status === 'STARTED') return null;
    if (result?.status === 'CONFIRMATION_REQUIRED') {
      const s = (result.scenario ?? {}) as Record<string, unknown>;
      pending = { scenarioId: Number(s.scenarioId), name: String(s.name ?? ''), code: String(s.code ?? ''),
        parameters: (s.parameters ?? {}) as Record<string, unknown> };
    }
  }
  return pending;
}

/** Monthly inflation path with repeats folded: [4, 4, 4, 3] -> "%4,0 × 3 · %3,0". */
export function inflationText(values: number[]): string {
  if (!values.length) return '–';
  const runs: { value: number; count: number }[] = [];
  values.forEach((v) => {
    const last = runs[runs.length - 1];
    if (last && last.value === v) last.count++;
    else runs.push({ value: v, count: 1 });
  });
  return runs.map((r) => `%${formatNumber(r.value, 1)}${r.count > 1 ? ` × ${r.count}` : ''}`).join(' · ');
}

export function ConfirmationCard({ pending, onConfirm, onCancel, disabled }: {
  pending: PendingConfirmation; onConfirm: () => void; onCancel: () => void; disabled?: boolean;
}) {
  const p = pending.parameters;
  const inflation = Array.isArray(p.monthlyInflationPct) ? (p.monthlyInflationPct as number[]) : [];
  const rows: [string, string][] = [
    ['USD/TRY', `%${formatNumber(Number(p.usdTryChangePct ?? 0), 1)}`],
    ['Politika faizi', `${formatNumber(Number(p.policyRateChangeBp ?? 0))} bp`],
    ['Aylık enflasyon', inflationText(inflation)],
    ['Ücret', `%${formatNumber(Number(p.wageIndexationPct ?? 0))} / ${String(p.wageAdjustmentLagMonths ?? '–')} ay`],
    ['Ufuk / yol', `${String(p.horizonMonths ?? '–')} ay · ${String(p.monteCarloPaths ?? '–')} yol`],
  ];
  return (
    <div className="mt-3 rounded-lg border border-warning/40 bg-warning-soft/40 p-4" role="group"
         aria-label="Simülasyon onayı">
      <div className="mb-2 flex items-center gap-2 text-sm font-semibold">
        <Info className="size-4 text-warning" /> Simülasyon onayı bekleniyor
      </div>
      <div className="mb-3 text-sm">{pending.name} <span className="text-xs text-muted-foreground">({pending.code},
        ID {pending.scenarioId})</span></div>
      <dl className="mb-4 grid grid-cols-1 gap-x-6 gap-y-1 text-xs sm:grid-cols-2">
        {rows.map(([k, v]) => (
          <div key={k} className="flex justify-between gap-2 border-b border-dashed border-border/70 py-1">
            <dt className="text-muted-foreground">{k}</dt><dd className="text-right font-medium">{v}</dd>
          </div>))}
      </dl>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" onClick={onConfirm} disabled={disabled}><Check /> Onayla ve başlat</Button>
        <Button size="sm" variant="outline" onClick={onCancel} disabled={disabled}><CircleSlash /> Vazgeç</Button>
      </div>
      <p className="mt-2 text-[11px] text-muted-foreground">Simülasyon maliyetli bir işlemdir; asistan onayınız olmadan
        başlatamaz.</p>
    </div>
  );
}

export function RunLinks({ runIds }: { runIds: number[] }) {
  if (!runIds.length) return null;
  return (
    <div className="mt-3 flex flex-wrap gap-2">
      {runIds.map((id) => (
        <Link key={id} to={`/runs/${id}`} className="inline-flex items-center gap-1.5 rounded-md border bg-card px-2.5
          py-1 text-xs font-medium text-primary transition-colors hover:bg-accent">
          Koşu #{id} sonuçları <ArrowRight className="size-3" />
        </Link>
      ))}
    </div>
  );
}

export function ValidationWarning({ validation }: { validation: { passed: boolean; unverified: string[] } | null }) {
  if (!validation || validation.passed) return null;
  return (
    <Tooltip content={<>Bu sayılar araç sonuçlarında doğrulanamadı: {validation.unverified.join(', ')}. Kontrol edin.</>}>
      <span className="inline-flex cursor-help items-center gap-1 text-[11px] font-medium text-warning">
        <AlertTriangle className="size-3" /> {validation.unverified.length} sayı doğrulanamadı
      </span>
    </Tooltip>
  );
}

/** Visual result of simulateSavingsGoal (coach). */
export function GoalCard({ call }: { call: ToolCall }) {
  const r = parse(call.result);
  if (!r || r.error || r.probabilityWithoutChanges === undefined) return null;
  const cuts = Array.isArray(r.categoryCuts) ? (r.categoryCuts as { category: string; cutPct: number }[]) : [];
  const changed = cuts.length > 0 || Number(r.extraMonthlySaving ?? 0) > 0;
  const band = (r.balanceAtHorizonWithChanges ?? {}) as { p10?: number; p50?: number; p90?: number };
  const assumptions = (r.assumptions ?? {}) as { targetInTodaysPrices?: number };
  return (
    <div className="mt-3 rounded-lg border bg-card p-4 shadow-card">
      <div className="mb-3 flex items-center gap-2">
        <div className="rounded-md bg-accent p-1.5 text-accent-foreground"><Target className="size-4" /></div>
        <div>
          <div className="text-sm font-semibold">Hedef: {formatMoney(Number(r.targetAmount))} · {String(r.months)} ay</div>
          <div className="text-[11px] text-muted-foreground">Bugünün fiyatlarıyla ≈ {formatMoney(assumptions.targetInTodaysPrices)}</div>
        </div>
      </div>
      <div className="space-y-3">
        <ProbabilityBar label="Şimdiki alışkanlıklarla" value={Number(r.probabilityWithoutChanges)} />
        {changed && <ProbabilityBar label="Değişikliklerle" value={Number(r.probabilityWithChanges)} />}
      </div>
      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <div className="rounded-md bg-muted/60 p-3">
          <div className="text-[11px] text-muted-foreground">%80 olasılık için aylık ek birikim</div>
          <div className="text-lg font-semibold">{r.requiredExtraMonthlySaving === null ? 'Ulaşılamıyor'
            : formatMoney(Number(r.requiredExtraMonthlySaving))}</div>
        </div>
        <div className="rounded-md bg-muted/60 p-3">
          <div className="text-[11px] text-muted-foreground">Dönem sonu birikim (beklenen)</div>
          <div className="text-lg font-semibold">{formatMoney(band.p50)}</div>
          <div className="text-[11px] text-muted-foreground">{formatMoney(band.p10)} – {formatMoney(band.p90)}</div>
        </div>
      </div>
      {cuts.length > 0 && (
        <div className="mt-3 flex flex-wrap items-center gap-1.5 text-xs">
          <span className="text-muted-foreground">Kesintiler:</span>
          {cuts.map((c) => <Badge key={c.category} tone="primary">{categoryLabel(c.category)} −%{formatNumber(c.cutPct)}</Badge>)}
          {Number(r.monthlySavingFromCuts) > 0 && <span className="text-muted-foreground">≈ {formatMoney(Number(
            r.monthlySavingFromCuts))} / ay</span>}
        </div>
      )}
    </div>
  );
}
