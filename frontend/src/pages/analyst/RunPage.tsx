import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Clock, Cpu, Gauge, ShieldAlert, Users, Wallet } from 'lucide-react';
import { scenariosApi } from '@/api/endpoints';
import type { RunResult, SimulationRun } from '@/api/types';
import { CompareChart, CumulativeDefaultChart, MigrationHeatmap, SegmentImpactChart } from '@/components/charts';
import { DeltaBadge, EmptyState, ErrorState, KpiCard, LoadingBlock, PageHeader } from '@/components/common';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Progress, Table, Td, Th, Tr } from '@/components/ui/primitives';
import { formatDate, formatMoney, formatMonth, formatNumber, formatPercent, formatPoints } from '@/lib/format';
import { segmentLabel } from '@/lib/labels';
import { parameterChips, RunStatusBadge } from './ScenariosPage';

const ACTIVE = ['PENDING', 'QUEUED', 'RUNNING'];

function Back() {
  return (
    <Link to="/scenarios" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
      <ArrowLeft className="size-4" /> Senaryolar
    </Link>
  );
}

function Results({ run, result }: { run: SimulationRun; result: RunResult }) {
  const p = result.portfolio;
  const d = result.deltaVsBaseline;
  const baselineMonthly = result.baseline?.portfolio.monthly ?? [];
  const cumulative = p.monthly.map((m, i) => ({ month: m.month, mean: m.cumulativeDefaultRate.mean,
    p5: m.cumulativeDefaultRate.p5, p95: m.cumulativeDefaultRate.p95,
    baseline: baselineMonthly[i]?.cumulativeDefaultRate.mean }));
  const segments = result.segments.map((s) => ({ segment: s.segment, scenario: s.defaultRate.mean,
    baseline: result.baseline?.segments.find((b) => b.segment === s.segment)?.defaultRate.mean }));
  return (
    <div className="space-y-5">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard label={`${result.horizonMonths} aylık temerrüt oranı`} icon={<Gauge />} tone="negative"
                 value={formatPercent(p.defaultRate.mean)}
                 hint={<>P5–P95: {formatPercent(p.defaultRate.p5)} – {formatPercent(p.defaultRate.p95)}{d && <span
                   className="ml-1.5"><DeltaBadge value={d.defaultRate.mean} format={(v) => formatPoints(v)} /></span>}</>} />
        <KpiCard label="Beklenen kayıp" icon={<Wallet />} tone="warning" value={formatMoney(p.expectedLoss, { compact: true })}
                 hint={<>EAD'nin {formatPercent(p.expectedLossPctOfEad / 100)}'i{d && <span className="ml-1.5">
                   <DeltaBadge value={d.loss.mean} format={(v) => formatMoney(v, { compact: true })} /></span>}</>} />
        <KpiCard label="Kuyruk kaybı (P99)" icon={<ShieldAlert />} tone="negative" value={formatMoney(p.lossP99, { compact: true })}
                 hint={`Beklenmeyen kayıp ${formatMoney(p.unexpectedLossP99, { compact: true })}`} />
        <KpiCard label="Churn oranı" icon={<Users />} value={formatPercent(p.churnRate.mean)}
                 hint={`${formatNumber(p.creditCustomers)} kredili / ${formatNumber(p.customers)} müşteri`} />
      </div>

      <Card>
        <CardHeader><CardTitle>Kümülatif temerrüt oranı</CardTitle>
          <CardDescription>Monte Carlo yollarının ortalaması ve P5–P95 aralığı{result.baseline ? ', baz senaryo ile' : ''}
          </CardDescription></CardHeader>
        <CardContent><CumulativeDefaultChart data={cumulative} /></CardContent>
      </Card>

      <div className="grid gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader><CardTitle>Segment etkisi</CardTitle>
            <CardDescription>Segment bazında ufuk temerrüt oranı</CardDescription></CardHeader>
          <CardContent>
            <SegmentImpactChart data={segments} />
            <Table className="mt-3">
              <thead><tr className="border-b"><Th>Segment</Th><Th className="text-right">Temerrüt</Th>
                <Th className="text-right">Baz farkı</Th><Th className="text-right">Beklenen kayıp</Th></tr></thead>
              <tbody>{result.segments.map((s) => {
                const delta = d?.segments.find((x) => x.segment === s.segment);
                return (
                  <Tr key={s.segment}>
                    <Td>{segmentLabel(s.segment)}</Td>
                    <Td className="text-right font-medium">{formatPercent(s.defaultRate.mean)}</Td>
                    <Td className="text-right">{delta ? <DeltaBadge value={delta.defaultRate.mean}
                      format={(v) => formatPoints(v)} /> : '–'}</Td>
                    <Td className="text-right">{formatMoney(s.expectedLoss, { compact: true })}</Td>
                  </Tr>);
              })}</tbody>
            </Table>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Risk derecesi göç matrisi</CardTitle>
            <CardDescription>Kredili müşteriler × yollar; başlangıç derecesinden ufuktaki duruma</CardDescription></CardHeader>
          <CardContent><MigrationHeatmap {...result.migration} /></CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader><CardTitle>En çok etkilenen müşteriler</CardTitle>
          <CardDescription>PD'si başlangıçtan ufka en çok artan müşteriler ve nedenleri</CardDescription></CardHeader>
        <CardContent className="px-0 pb-2">
          {!result.topCustomers.length ? <div className="px-5"><EmptyState title="Liste yok" /></div> : (
            <Table>
              <thead><tr className="border-b"><Th className="pl-5">Müşteri</Th><Th>Segment</Th><Th className="text-right">PD
                başlangıç → ufuk</Th><Th className="text-right">Temerrüt olasılığı</Th><Th className="pr-5">Nedenler</Th></tr></thead>
              <tbody>{result.topCustomers.slice(0, 10).map((c) => (
                <Tr key={c.customerId}>
                  <Td className="pl-5 font-medium"><Link className="hover:text-primary" to={`/customers/${c.customerId}`}>
                    #{c.customerId}</Link></Td>
                  <Td>{segmentLabel(c.segment)}</Td>
                  <Td className="text-right">{formatPercent(c.pdStart)} → <span className="font-semibold text-negative">
                    {formatPercent(c.pdHorizon)}</span></Td>
                  <Td className="text-right">{formatPercent(c.defaultProbability, 0)}</Td>
                  <Td className="max-w-md whitespace-normal pr-5 text-xs text-muted-foreground">
                    {c.reasons.slice(0, 2).map((r) => r.textTr).join(' · ')}</Td>
                </Tr>))}
              </tbody>
            </Table>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle>Makro patika</CardTitle><CardDescription>Senaryonun çeyrek sonu değerleri</CardDescription></CardHeader>
          <CardContent className="px-0 pb-2">
            <Table>
              <thead><tr className="border-b"><Th className="pl-5">Ay</Th><Th className="text-right">Aylık enflasyon</Th>
                <Th className="text-right">USD/TRY</Th><Th className="pr-5 text-right">Politika faizi</Th></tr></thead>
              <tbody>{result.macroPath.filter((_, i) => i % 3 === 2 || i === 0).map((m) => (
                <Tr key={m.month}><Td className="pl-5">{formatMonth(m.month)}</Td>
                  <Td className="text-right">%{formatNumber(m.inflationPct, 2)}</Td>
                  <Td className="text-right">{formatNumber(m.usdTry, 2)}</Td>
                  <Td className="pr-5 text-right">%{formatNumber(m.policyRatePct, 1)}</Td></Tr>))}
              </tbody>
            </Table>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Koşu bilgisi</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-sm">
            <Info icon={<Clock />} label="Süre" value={`${formatNumber(run.durationSeconds)} sn`} />
            <Info icon={<Cpu />} label="Bellek tepesi" value={run.peakRssMb ? `${formatNumber(run.peakRssMb)} MB` : '–'} />
            <Info label="Başlangıç" value={result.startDate ? formatDate(result.startDate) : '–'} />
            <Info label="Yol sayısı / ufuk" value={`${formatNumber(result.paths)} yol · ${result.horizonMonths} ay`} />
            <Info label="Modeller" value={`PD ${result.modelVersions.pd} · churn ${result.modelVersions.churn}`} />
            <div className="flex flex-wrap gap-1.5 pt-2">{parameterChips(result.scenario).map((c) =>
              <Badge key={c} tone="outline" className="font-normal">{c}</Badge>)}</div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function Info({ icon, label, value }: { icon?: React.ReactNode; label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b pb-2 last:border-0">
      <span className="flex items-center gap-2 text-muted-foreground [&_svg]:size-4">{icon}{label}</span>
      <span className="text-right font-medium">{value}</span>
    </div>
  );
}

export function RunPage() {
  const id = Number(useParams().id);
  const run = useQuery({ queryKey: ['run', id], queryFn: () => scenariosApi.runDetail(id),
    refetchInterval: (q) => (q.state.data && ACTIVE.includes(q.state.data.status) ? 3000 : false) });
  const r = run.data;
  return (
    <div className="space-y-5">
      <Back />
      <PageHeader eyebrow={`Koşu #${id}`} title={r ? r.scenarioName : 'Simülasyon koşusu'}
        description={r ? `${r.scenarioCode} · ${r.startDate ? `${formatDate(r.startDate)} snapshot'ından` : 'güncel portföyden'}
          ${r.baselineScenarioId ? ' · baz senaryo ile karşılaştırmalı' : ''}` : undefined}
        actions={r && <RunStatusBadge run={r} />} />
      {run.isLoading ? <LoadingBlock rows={6} /> : run.isError ? <ErrorState error={run.error} onRetry={() => run.refetch()} />
        : r && (ACTIVE.includes(r.status) ? (
          <Card className="p-8">
            <div className="mx-auto max-w-lg space-y-4 text-center">
              <div className="text-lg font-semibold">Simülasyon çalışıyor</div>
              <p className="text-sm text-muted-foreground">Monte Carlo yolları ML servisinde hesaplanıyor; sayfa birkaç
                saniyede bir güncellenir.</p>
              <Progress value={r.progress} />
              <div className="text-sm font-medium">%{formatNumber(r.progress)}</div>
            </div>
          </Card>
        ) : r.status === 'FAILED' ? <ErrorState error={new Error(r.error ?? 'Koşu başarısız')} title="Koşu başarısız oldu" />
          : r.result ? <Results run={r} result={r.result} /> : <EmptyState title="Sonuç yok" />)}
    </div>
  );
}

export function ComparePage() {
  const [params] = useSearchParams();
  const ids = (params.get('ids') ?? '').split(',').map(Number).filter((n) => n > 0);
  const compare = useQuery({ queryKey: ['compare', ids.join(',')], queryFn: () => scenariosApi.compare(ids),
    enabled: ids.length > 0 });
  const rows = compare.data?.runs ?? [];
  const segments = [...new Set(rows.flatMap((r) => Object.keys(r.segmentDefaultRate)))];
  return (
    <div className="space-y-5">
      <Back />
      <PageHeader eyebrow="Karşılaştırma" title={`Koşu karşılaştırması (${ids.length})`}
        description="Farklar ilk seçilen koşuya göredir." />
      {ids.length < 2 ? <EmptyState title="En az iki koşu seçin" /> : compare.isLoading ? <LoadingBlock rows={6} />
        : compare.isError ? <ErrorState error={compare.error} /> : (
          <>
            <Card><CardHeader><CardTitle>Temerrüt ve beklenen kayıp</CardTitle></CardHeader>
              <CardContent><CompareChart data={rows.map((r) => ({ label: `#${r.runId} ${r.scenarioCode}`,
                defaultRate: r.defaultRate.mean, elPct: r.expectedLossPctOfEad / 100 }))} /></CardContent></Card>
            <Card>
              <CardContent className="px-0 py-2">
                <Table>
                  <thead><tr className="border-b"><Th className="pl-5">Ölçüt</Th>{rows.map((r) => (
                    <Th key={r.runId} className="text-right"><Link to={`/runs/${r.runId}`} className="hover:text-foreground">
                      #{r.runId} · {r.scenarioName}</Link></Th>))}</tr></thead>
                  <tbody>
                    <CompareRow label="Temerrüt oranı" rows={rows} value={(r) => formatPercent(r.defaultRate.mean)}
                                delta={(r) => r.defaultRateDeltaVsFirst} deltaFormat={(v) => formatPoints(v)} />
                    <CompareRow label="Temerrüt P95" rows={rows} value={(r) => formatPercent(r.defaultRate.p95)} />
                    <CompareRow label="Churn oranı" rows={rows} value={(r) => formatPercent(r.churnRate.mean)} />
                    <CompareRow label="Beklenen kayıp" rows={rows} value={(r) => formatMoney(r.expectedLoss, { compact: true })}
                                delta={(r) => r.expectedLossDeltaVsFirst} deltaFormat={(v) => formatMoney(v, { compact: true })} />
                    <CompareRow label="P99 kayıp" rows={rows} value={(r) => formatMoney(r.lossP99, { compact: true })} />
                    <CompareRow label="EL / EAD" rows={rows} value={(r) => formatPercent(r.expectedLossPctOfEad / 100)} />
                    {segments.map((s) => <CompareRow key={s} label={`Temerrüt · ${segmentLabel(s)}`} rows={rows}
                                                     value={(r) => formatPercent(r.segmentDefaultRate[s])} />)}
                  </tbody>
                </Table>
              </CardContent>
            </Card>
          </>
        )}
    </div>
  );
}

type CompareRowData = NonNullable<ReturnType<typeof scenariosApi.compare> extends Promise<infer T> ? T : never>['runs'][number];

function CompareRow({ label, rows, value, delta, deltaFormat }: {
  label: string; rows: CompareRowData[]; value: (r: CompareRowData) => string;
  delta?: (r: CompareRowData) => number; deltaFormat?: (v: number) => string;
}) {
  return (
    <Tr>
      <Td className="pl-5 text-muted-foreground">{label}</Td>
      {rows.map((r, i) => (
        <Td key={r.runId} className="text-right font-medium">
          {value(r)}{delta && deltaFormat && i > 0 && <span className="ml-2"><DeltaBadge value={delta(r)} format={deltaFormat} /></span>}
        </Td>))}
    </Tr>
  );
}
