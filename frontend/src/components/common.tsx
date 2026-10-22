/** Shared building blocks of the screens: headers, KPI tiles, state views, risk and delta badges. */
import * as React from 'react';
import { AlertTriangle, ArrowDownRight, ArrowUpRight, Inbox, Loader2, RefreshCw } from 'lucide-react';
import { Badge, Card, Skeleton } from '@/components/ui/primitives';
import { Button } from '@/components/ui/button';
import { Tooltip } from '@/components/ui/radix';
import { ApiError } from '@/api/client';
import { formatPercent } from '@/lib/format';
import { gradeBand, gradeColor, gradeRange } from '@/lib/risk';
import { cn } from '@/lib/utils';

export function PageHeader({ title, description, actions, eyebrow }: {
  title: string; description?: React.ReactNode; actions?: React.ReactNode; eyebrow?: React.ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div className="space-y-1">
        {eyebrow && <div className="text-xs font-medium uppercase tracking-wider text-primary">{eyebrow}</div>}
        <h1 className="page-title">{title}</h1>
        {description && <p className="max-w-3xl text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function KpiCard({ label, value, hint, icon, tone = 'neutral', loading }: {
  label: string; value: React.ReactNode; hint?: React.ReactNode; icon?: React.ReactNode;
  tone?: 'neutral' | 'positive' | 'negative' | 'warning'; loading?: boolean;
}) {
  const accent = { neutral: 'text-primary bg-accent', positive: 'text-positive bg-positive-soft',
    negative: 'text-negative bg-negative-soft', warning: 'text-warning bg-warning-soft' }[tone];
  return (
    <Card className="animate-fade-up p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 space-y-1.5">
          <div className="text-xs font-medium text-muted-foreground">{label}</div>
          {loading ? <Skeleton className="h-7 w-28" />
            : <div className="truncate text-2xl font-semibold tracking-tight">{value}</div>}
          {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
        </div>
        {icon && <div className={cn('rounded-lg p-2 [&_svg]:size-5', accent)}>{icon}</div>}
      </div>
    </Card>
  );
}

export function SectionTitle({ children, action }: { children: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="mb-3 flex items-center justify-between gap-2">
      <h2 className="section-title">{children}</h2>
      {action}
    </div>
  );
}

export function LoadingBlock({ className, rows = 3 }: { className?: string; rows?: number }) {
  return (
    <div className={cn('space-y-3', className)} aria-busy="true" aria-label="Yükleniyor">
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} className={cn('h-5', i === 0 ? 'w-2/3' : 'w-full')} />)}
    </div>
  );
}

export function ChartSkeleton({ height = 260 }: { height?: number }) {
  return <Skeleton className="w-full" style={{ height }} aria-label="Grafik yükleniyor" />;
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0 || error.status >= 502) return 'Sunucuya ulaşılamadı. Servislerin çalıştığından emin olun.';
    if (error.status === 404) return 'Kayıt bulunamadı.';
    return error.message;
  }
  if (error instanceof TypeError) return 'Sunucuya ulaşılamadı (backend kapalı olabilir).';
  return error instanceof Error ? error.message : 'Beklenmeyen bir hata oluştu.';
}

export function ErrorState({ error, onRetry, title = 'Veri yüklenemedi', compact }: {
  error: unknown; onRetry?: () => void; title?: string; compact?: boolean;
}) {
  return (
    <div role="alert" className={cn('flex flex-col items-center justify-center gap-2 rounded-lg border '
      + 'border-dashed border-negative/40 bg-negative-soft/40 text-center', compact ? 'p-4' : 'p-8')}>
      <AlertTriangle className="size-5 text-negative" />
      <div className="text-sm font-medium">{title}</div>
      <div className="max-w-md text-xs text-muted-foreground">{errorText(error)}</div>
      {onRetry && <Button size="sm" variant="outline" onClick={onRetry}><RefreshCw /> Tekrar dene</Button>}
    </div>
  );
}

export function EmptyState({ title, description, action, icon }: {
  title: string; description?: React.ReactNode; action?: React.ReactNode; icon?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed p-8 text-center">
      <div className="rounded-full bg-muted p-3 text-muted-foreground [&_svg]:size-5">{icon ?? <Inbox />}</div>
      <div className="text-sm font-medium">{title}</div>
      {description && <div className="max-w-md text-xs text-muted-foreground">{description}</div>}
      {action}
    </div>
  );
}

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn('size-4 animate-spin', className)} />;
}

/** Colour-coded risk grade chip with the PD band as tooltip. */
export function RiskGradeBadge({ grade, size = 'md' }: { grade: number | null | undefined; size?: 'sm' | 'md' }) {
  if (grade === null || grade === undefined) return <span className="text-muted-foreground">–</span>;
  const band = gradeBand(grade);
  return (
    <Tooltip content={`${band.label} · PD ${gradeRange(grade)}`}>
      <span className={cn('inline-flex items-center justify-center rounded-md font-semibold text-white tabular-nums',
        size === 'sm' ? 'h-5 min-w-5 px-1 text-[11px]' : 'h-6 min-w-7 px-1.5 text-xs')}
            style={{ backgroundColor: gradeColor(grade) }} aria-label={`Risk derecesi ${grade}`}>
        {grade}
      </span>
    </Tooltip>
  );
}

/**
 * Signed change as a coloured chip. {@code higherIsWorse} for risk metrics (a PD increase is shown in red).
 */
export function DeltaBadge({ value, format, higherIsWorse = true }: {
  value: number | null | undefined; format: (v: number) => string; higherIsWorse?: boolean;
}) {
  if (value === null || value === undefined || !Number.isFinite(value)) return null;
  const up = value > 0;
  const bad = higherIsWorse ? up : !up;
  if (Math.abs(value) < 1e-12) return <Badge tone="neutral">{format(0)}</Badge>;
  return (
    <Badge tone={bad ? 'negative' : 'positive'}>
      {up ? <ArrowUpRight className="size-3" /> : <ArrowDownRight className="size-3" />}
      {format(value)}
    </Badge>
  );
}

export function ProbabilityBar({ value, label }: { value: number; label?: string }) {
  const tone = value >= 0.7 ? 'bg-positive' : value >= 0.4 ? 'bg-warning' : 'bg-negative';
  return (
    <div className="space-y-1.5">
      <div className="flex items-baseline justify-between text-sm">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-semibold">{formatPercent(value, 0)}</span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-muted">
        <div className={cn('h-full rounded-full transition-[width] duration-700', tone)}
             style={{ width: `${Math.max(2, value * 100)}%` }} />
      </div>
    </div>
  );
}

export function Dot({ tone }: { tone: 'positive' | 'negative' | 'warning' | 'neutral' }) {
  const color = { positive: 'bg-positive', negative: 'bg-negative', warning: 'bg-warning',
    neutral: 'bg-muted-foreground' }[tone];
  return <span className={cn('inline-block size-2 rounded-full', color)} />;
}
