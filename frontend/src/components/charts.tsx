/** Charts (Recharts) styled with the design tokens; numbers formatted tr-TR. */
import * as React from 'react';
import {
  Area, Bar, BarChart, CartesianGrid, Cell, ComposedChart, Legend, Line, LineChart, ReferenceLine,
  ResponsiveContainer, Tooltip as RTooltip, XAxis, YAxis,
} from 'recharts';
import { formatMoney, formatMonth, formatNumber, formatPercent } from '@/lib/format';
import { gradeColor } from '@/lib/risk';
import { segmentLabel } from '@/lib/labels';
import { cn } from '@/lib/utils';

const C = {
  grid: 'hsl(var(--border))',
  axis: 'hsl(var(--muted-foreground))',
  c1: 'hsl(var(--chart-1))',
  c2: 'hsl(var(--chart-2))',
  c3: 'hsl(var(--chart-3))',
  c4: 'hsl(var(--chart-4))',
  c5: 'hsl(var(--chart-5))',
};

const axisProps = { stroke: C.axis, fontSize: 11, tickLine: false, axisLine: false } as const;

function TooltipBox({ title, rows }: { title?: React.ReactNode; rows: { label: string; value: string;
  color?: string }[] }) {
  return (
    <div className="min-w-40 rounded-md border bg-popover px-3 py-2 text-xs shadow-lift">
      {title && <div className="mb-1 font-medium">{title}</div>}
      {rows.map((r) => (
        <div key={r.label} className="flex items-center justify-between gap-4">
          <span className="flex items-center gap-1.5 text-muted-foreground">
            {r.color && <span className="inline-block size-2 rounded-full" style={{ background: r.color }} />}
            {r.label}
          </span>
          <span className="font-medium tabular-nums">{r.value}</span>
        </div>
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ portfolio

export function GradeDistributionChart({ data, height = 240 }: { data: { grade: number; count: number }[];
  height?: number }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={C.grid} strokeDasharray="3 3" />
        <XAxis dataKey="grade" {...axisProps} />
        <YAxis {...axisProps} tickFormatter={(v: number) => formatNumber(v)} />
        <RTooltip cursor={{ fill: 'hsl(var(--muted))' }} content={({ active, payload }) => active && payload?.length
          ? <TooltipBox title={`Derece ${payload[0].payload.grade}`}
                        rows={[{ label: 'Müşteri', value: formatNumber(payload[0].value as number) }]} /> : null} />
        <Bar dataKey="count" radius={[4, 4, 0, 0]} maxBarSize={36}>
          {data.map((d) => <Cell key={d.grade} fill={gradeColor(d.grade)} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}

export function SegmentRiskChart({ data, height = 260 }: {
  data: { segment: string; meanPd: number; meanChurn: number }[]; height?: number;
}) {
  const rows = data.map((d) => ({ ...d, label: segmentLabel(d.segment) }));
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={rows} margin={{ top: 8, right: 8, left: -4, bottom: 0 }} barGap={4}>
        <CartesianGrid vertical={false} stroke={C.grid} strokeDasharray="3 3" />
        <XAxis dataKey="label" {...axisProps} interval={0} />
        <YAxis {...axisProps} tickFormatter={(v: number) => formatPercent(v, 0)} />
        <RTooltip cursor={{ fill: 'hsl(var(--muted))' }} content={({ active, payload }) => active && payload?.length
          ? <TooltipBox title={payload[0].payload.label} rows={[
            { label: 'Ortalama PD', value: formatPercent(payload[0].payload.meanPd), color: C.c5 },
            { label: 'Ortalama churn', value: formatPercent(payload[0].payload.meanChurn), color: C.c2 }]} />
          : null} />
        <Legend iconType="circle" iconSize={8} wrapperStyle={{ fontSize: 12 }} />
        <Bar dataKey="meanPd" name="Ortalama PD (6 ay)" fill={C.c5} radius={[4, 4, 0, 0]} maxBarSize={28} />
        <Bar dataKey="meanChurn" name="Ortalama churn (6 ay)" fill={C.c2} radius={[4, 4, 0, 0]} maxBarSize={28} />
      </BarChart>
    </ResponsiveContainer>
  );
}

export function MacroChart({ data, height = 260 }: {
  data: { month: string; inflation?: number; policy?: number; usdTry?: number }[]; height?: number;
}) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={data} margin={{ top: 8, right: 4, left: -8, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={C.grid} strokeDasharray="3 3" />
        <XAxis dataKey="month" {...axisProps} tickFormatter={formatMonth} minTickGap={24} />
        <YAxis yAxisId="pct" {...axisProps} tickFormatter={(v: number) => `%${formatNumber(v)}`} />
        <YAxis yAxisId="fx" orientation="right" {...axisProps} domain={['auto', 'auto']}
               tickFormatter={(v: number) => formatNumber(v)} />
        <RTooltip content={({ active, payload, label }) => active && payload?.length
          ? <TooltipBox title={formatMonth(String(label))} rows={payload.map((p) => ({
            label: String(p.name), color: String(p.color),
            value: p.dataKey === 'usdTry' ? formatNumber(p.value as number, 2) : `%${formatNumber(p.value as number, 2)}`,
          }))} /> : null} />
        <Legend iconType="circle" iconSize={8} wrapperStyle={{ fontSize: 12 }} />
        <Line yAxisId="pct" dataKey="policy" name="Politika faizi (yıllık)" stroke={C.c1} strokeWidth={2} dot={false} />
        <Line yAxisId="pct" dataKey="inflation" name="Aylık enflasyon" stroke={C.c3} strokeWidth={2} dot={false} />
        <Line yAxisId="fx" dataKey="usdTry" name="USD/TRY (sağ eksen)" stroke={C.c2} strokeWidth={2} dot={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}

// ------------------------------------------------------------------ twin

export function FeatureHistoryChart({ data, kind, highlight, height = 220 }: {
  data: { date: string; value: number | null }[]; kind: 'pct' | 'months' | 'money'; highlight?: string | null;
  height?: number;
}) {
  const fmt = (v: number) => kind === 'pct' ? formatPercent(v, 0) : kind === 'money' ? formatMoney(v, { compact: true })
    : formatNumber(v, 1);
  return (
    <ResponsiveContainer width="100%" height={height}>
      <ComposedChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="hist" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor={C.c1} stopOpacity={0.25} />
            <stop offset="100%" stopColor={C.c1} stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid vertical={false} stroke={C.grid} strokeDasharray="3 3" />
        <XAxis dataKey="date" {...axisProps} tickFormatter={formatMonth} minTickGap={28} />
        <YAxis {...axisProps} width={64} tickFormatter={fmt} />
        <RTooltip content={({ active, payload }) => active && payload?.length
          ? <TooltipBox title={formatMonth(String(payload[0].payload.date))}
                        rows={[{ label: 'Değer', value: payload[0].value === null ? '–' : fmt(payload[0].value as number) }]} />
          : null} />
        {highlight && <ReferenceLine x={highlight} stroke={C.c3} strokeDasharray="4 3"
                                     label={{ value: 'Seçili tarih', fill: C.axis, fontSize: 10, position: 'insideTop' }} />}
        <Area dataKey="value" stroke={C.c1} strokeWidth={2} fill="url(#hist)" connectNulls />
      </ComposedChart>
    </ResponsiveContainer>
  );
}

/** P10-P90 band with the P50 line (cash-flow forecasts). */
export function FanChart({ data, height = 260, valueFormat, label = 'Bakiye' }: {
  data: { month: string; p10: number; p50: number; p90: number }[]; height?: number;
  valueFormat?: (v: number) => string; label?: string;
}) {
  const fmt = valueFormat ?? ((v: number) => formatMoney(v, { compact: true }));
  const rows = data.map((d) => ({ ...d, band: [d.p10, d.p90] as [number, number] }));
  return (
    <ResponsiveContainer width="100%" height={height}>
      <ComposedChart data={rows} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={C.grid} strokeDasharray="3 3" />
        <XAxis dataKey="month" {...axisProps} tickFormatter={formatMonth} minTickGap={24} />
        <YAxis {...axisProps} width={72} tickFormatter={fmt} />
        <RTooltip content={({ active, payload }) => active && payload?.length
          ? <TooltipBox title={formatMonth(String(payload[0].payload.month))} rows={[
            { label: 'İyimser (P90)', value: fmt(payload[0].payload.p90) },
            { label: 'Beklenen (P50)', value: fmt(payload[0].payload.p50), color: C.c1 },
            { label: 'Kötümser (P10)', value: fmt(payload[0].payload.p10) }]} /> : null} />
        <Area dataKey="band" name={`${label} aralığı (P10–P90)`} stroke="none" fill={C.c1} fillOpacity={0.18} />
        <Line dataKey="p50" name={`${label} (P50)`} stroke={C.c1} strokeWidth={2.25} dot={false} />
        <Line dataKey="p10" stroke={C.c1} strokeOpacity={0.45} strokeDasharray="3 3" dot={false} legendType="none" />
        <Line dataKey="p90" stroke={C.c1} strokeOpacity={0.45} strokeDasharray="3 3" dot={false} legendType="none" />
      </ComposedChart>
    </ResponsiveContainer>
  );
}

// ------------------------------------------------------------------ simulation

export function CumulativeDefaultChart({ data, height = 280 }: {
  data: { month: string; mean: number; p5: number; p95: number; baseline?: number }[]; height?: number;
}) {
  const rows = data.map((d) => ({ ...d, band: [d.p5, d.p95] as [number, number] }));
  return (
    <ResponsiveContainer width="100%" height={height}>
      <ComposedChart data={rows} margin={{ top: 8, right: 8, left: -4, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={C.grid} strokeDasharray="3 3" />
        <XAxis dataKey="month" {...axisProps} tickFormatter={formatMonth} minTickGap={24} />
        <YAxis {...axisProps} tickFormatter={(v: number) => formatPercent(v, 1)} />
        <RTooltip content={({ active, payload }) => active && payload?.length
          ? <TooltipBox title={formatMonth(String(payload[0].payload.month))} rows={[
            { label: 'Senaryo (ortalama)', value: formatPercent(payload[0].payload.mean), color: C.c5 },
            { label: 'P5 – P95', value: `${formatPercent(payload[0].payload.p5)} – ${formatPercent(payload[0].payload.p95)}` },
            ...(payload[0].payload.baseline !== undefined
              ? [{ label: 'Baz senaryo', value: formatPercent(payload[0].payload.baseline), color: C.axis }] : [])]} />
          : null} />
        <Legend iconType="circle" iconSize={8} wrapperStyle={{ fontSize: 12 }} />
        <Area dataKey="band" name="P5–P95 aralığı" stroke="none" fill={C.c5} fillOpacity={0.15} />
        <Line dataKey="mean" name="Kümülatif temerrüt (ortalama)" stroke={C.c5} strokeWidth={2.25} dot={false} />
        {rows.some((r) => r.baseline !== undefined) && (
          <Line dataKey="baseline" name="Baz senaryo" stroke={C.axis} strokeDasharray="5 4" strokeWidth={1.5} dot={false} />
        )}
      </ComposedChart>
    </ResponsiveContainer>
  );
}

export function SegmentImpactChart({ data, height = 260 }: {
  data: { segment: string; scenario: number; baseline?: number }[]; height?: number;
}) {
  const rows = data.map((d) => ({ ...d, label: segmentLabel(d.segment) }));
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={rows} margin={{ top: 8, right: 8, left: -4, bottom: 0 }} barGap={4}>
        <CartesianGrid vertical={false} stroke={C.grid} strokeDasharray="3 3" />
        <XAxis dataKey="label" {...axisProps} interval={0} />
        <YAxis {...axisProps} tickFormatter={(v: number) => formatPercent(v, 0)} />
        <RTooltip cursor={{ fill: 'hsl(var(--muted))' }} content={({ active, payload }) => active && payload?.length
          ? <TooltipBox title={payload[0].payload.label} rows={payload.map((p) => ({ label: String(p.name),
            value: formatPercent(p.value as number), color: String(p.color) }))} /> : null} />
        <Legend iconType="circle" iconSize={8} wrapperStyle={{ fontSize: 12 }} />
        {rows.some((r) => r.baseline !== undefined) && (
          <Bar dataKey="baseline" name="Baz senaryo" fill={C.axis} fillOpacity={0.45} radius={[4, 4, 0, 0]}
               maxBarSize={28} />
        )}
        <Bar dataKey="scenario" name="Senaryo" fill={C.c5} radius={[4, 4, 0, 0]} maxBarSize={28} />
      </BarChart>
    </ResponsiveContainer>
  );
}

export function CompareChart({ data, height = 260 }: { data: { label: string; defaultRate: number; elPct: number }[];
  height?: number }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 8, right: 8, left: -4, bottom: 0 }} barGap={4}>
        <CartesianGrid vertical={false} stroke={C.grid} strokeDasharray="3 3" />
        <XAxis dataKey="label" {...axisProps} interval={0} />
        <YAxis {...axisProps} tickFormatter={(v: number) => formatPercent(v, 1)} />
        <RTooltip cursor={{ fill: 'hsl(var(--muted))' }} content={({ active, payload }) => active && payload?.length
          ? <TooltipBox title={payload[0].payload.label} rows={payload.map((p) => ({ label: String(p.name),
            value: formatPercent(p.value as number), color: String(p.color) }))} /> : null} />
        <Legend iconType="circle" iconSize={8} wrapperStyle={{ fontSize: 12 }} />
        <Bar dataKey="defaultRate" name="Temerrüt oranı" fill={C.c5} radius={[4, 4, 0, 0]} maxBarSize={32} />
        <Bar dataKey="elPct" name="Beklenen kayıp / EAD" fill={C.c3} radius={[4, 4, 0, 0]} maxBarSize={32} />
      </BarChart>
    </ResponsiveContainer>
  );
}

/** Rating migration heat map: rows = start grade, columns = end grade / DEFAULT / CHURN. */
export function MigrationHeatmap({ from, to, rates, counts }: {
  from: number[]; to: string[]; rates: number[][]; counts: number[][];
}) {
  const label = (t: string) => (t === 'DEFAULT' ? 'T' : t === 'CHURN' ? 'A' : t);
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-0.5 text-[11px]" aria-label="Risk derecesi göç matrisi">
        <thead>
          <tr>
            <th className="px-1 text-left font-medium text-muted-foreground">Başl. ↓ / Son →</th>
            {to.map((t) => (
              <th key={t} className={cn('px-1 py-1 font-medium', t === 'DEFAULT' ? 'text-negative'
                : t === 'CHURN' ? 'text-info' : 'text-muted-foreground')} title={t === 'DEFAULT' ? 'Temerrüt'
                : t === 'CHURN' ? 'Ayrılma' : `Derece ${t}`}>{label(t)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {from.map((g, i) => counts[i].some((c) => c > 0) && (
            <tr key={g}>
              <th className="pr-1 text-right font-medium text-muted-foreground">{g}</th>
              {to.map((t, j) => {
                const r = rates[i][j];
                const terminal = t === 'DEFAULT' || t === 'CHURN';
                const color = t === 'DEFAULT' ? '357 70% 48%' : t === 'CHURN' ? '213 80% 48%' : '178 72% 30%';
                return (
                  <td key={t} title={`${g} → ${t}: ${formatPercent(r, 1)} (${formatNumber(counts[i][j])})`}
                      className={cn('h-7 min-w-9 rounded-sm text-center tabular-nums', i === j && !terminal
                        ? 'ring-1 ring-inset ring-foreground/20' : '')}
                      style={{ backgroundColor: `hsl(${color} / ${r === 0 ? 0.03 : 0.1 + Math.min(0.85, r * 1.1)})`,
                        color: r > 0.45 ? 'white' : undefined }}>
                    {r >= 0.005 ? formatNumber(r * 100, 0) : ''}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-[11px] text-muted-foreground">
        Hücreler başlangıç derecesinden ufuk sonundaki duruma geçiş oranını (%) gösterir. T: temerrüt, A: ayrılma (churn).
      </p>
    </div>
  );
}
