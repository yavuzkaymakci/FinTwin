import { useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AlertOctagon, Gauge, Users, Wallet } from 'lucide-react';
import { portfolioApi } from '@/api/endpoints';
import type { MacroIndicator } from '@/api/types';
import { MacroChart, GradeDistributionChart, SegmentRiskChart } from '@/components/charts';
import {
  ChartSkeleton, EmptyState, ErrorState, KpiCard, LoadingBlock, PageHeader, RiskGradeBadge, SectionTitle,
} from '@/components/common';
import { Card, CardContent, CardDescription, CardHeader, CardTitle, Table, Td, Th, Tr } from '@/components/ui/primitives';
import { formatDateTime, formatMoney, formatNumber, formatPercent } from '@/lib/format';
import { segmentLabel } from '@/lib/labels';

function useMacro() {
  return useQuery({
    queryKey: ['macro'],
    queryFn: async () => {
      const [inflation, policy, usd] = await Promise.all(['MONTHLY_INFLATION', 'POLICY_RATE', 'USD_TRY']
        .map((i) => portfolioApi.macro(i)));
      const byMonth = new Map<string, { month: string; inflation?: number; policy?: number; usdTry?: number }>();
      const put = (rows: MacroIndicator[], key: 'inflation' | 'policy' | 'usdTry') => rows.forEach((r) => {
        const month = r.period.slice(0, 7);
        byMonth.set(month, { ...(byMonth.get(month) ?? { month }), [key]: Number(r.value) });
      });
      put(inflation, 'inflation');
      put(policy, 'policy');
      put(usd, 'usdTry');
      return [...byMonth.values()].sort((a, b) => a.month.localeCompare(b.month));
    },
    staleTime: 10 * 60_000,
  });
}

export function DashboardPage() {
  const navigate = useNavigate();
  const summary = useQuery({ queryKey: ['portfolio-summary'], queryFn: () => portfolioApi.summary(10) });
  const macro = useMacro();
  const s = summary.data;
  const grades = useMemo(() => s ? Object.entries(s.customersByGrade).map(([g, c]) => ({ grade: Number(g), count: c }))
    : [], [s]);

  return (
    <div className="space-y-6">
      <PageHeader eyebrow="Risk analisti" title="Portföy paneli"
        description={s?.scoredAt ? `Son risk skorlaması: ${formatDateTime(s.scoredAt)} · Her müşterinin son skoru ve
          güncel ikizi üzerinden.` : 'Müşterilerin son risk skorları ve güncel ikizleri üzerinden portföy görünümü.'} />

      {summary.isError ? <ErrorState error={summary.error} onRetry={() => summary.refetch()} /> : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <KpiCard loading={summary.isLoading} label="Aktif müşteri" icon={<Users />}
                   value={formatNumber(s?.activeCustomers)} hint={`${formatNumber(s?.scoredCustomers)} müşteri skorlu`} />
          <KpiCard loading={summary.isLoading} label="Ortalama 6 aylık PD" icon={<Gauge />} tone="warning"
                   value={formatPercent(s?.meanPd)} hint={`Ortalama churn ${formatPercent(s?.meanChurn)}`} />
          <KpiCard loading={summary.isLoading} label="Beklenen kayıp (6 ay)" icon={<Wallet />} tone="negative"
                   value={formatMoney(s?.expectedLoss, { compact: true })}
                   hint={`PD × LGD %${formatNumber((s?.lgd ?? 0) * 100)} × borç · toplam borç ${formatMoney(s?.totalDebt,
                     { compact: true })}`} />
          <KpiCard loading={summary.isLoading} label="Yüksek riskli müşteri" icon={<AlertOctagon />} tone="negative"
                   value={formatNumber(s?.highRiskCustomers)} hint="Risk derecesi 8 ve üzeri" />
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Risk derecesi dağılımı</CardTitle>
            <CardDescription>1 en düşük, 10 en yüksek risk (6 aylık PD ana ölçeği)</CardDescription>
          </CardHeader>
          <CardContent>{summary.isLoading ? <ChartSkeleton height={240} /> : grades.length
            ? <GradeDistributionChart data={grades} /> : <EmptyState title="Skor yok" />}</CardContent>
        </Card>
        <Card className="lg:col-span-3">
          <CardHeader>
            <CardTitle>Segment bazında risk</CardTitle>
            <CardDescription>Segment ortalaması 6 aylık temerrüt (PD) ve ayrılma (churn) olasılığı</CardDescription>
          </CardHeader>
          <CardContent>{summary.isLoading ? <ChartSkeleton /> : s?.segments.length
            ? <SegmentRiskChart data={s.segments} /> : <EmptyState title="Segment verisi yok" />}</CardContent>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader>
            <CardTitle>En riskli müşteriler</CardTitle>
            <CardDescription>6 aylık PD'ye göre ilk 10 · satıra tıklayarak ikize gidin</CardDescription>
          </CardHeader>
          <CardContent className="px-0 pb-2">
            {summary.isLoading ? <LoadingBlock className="px-5" rows={6} /> : (
              <Table>
                <thead><tr className="border-b"><Th className="pl-5">Müşteri</Th><Th>Segment</Th><Th>Derece</Th>
                  <Th className="text-right">PD</Th><Th className="text-right">Kart kullanımı</Th>
                  <Th className="pr-5 text-right">Borç</Th></tr></thead>
                <tbody>
                  {s?.topRiskCustomers.map((c) => (
                    <Tr key={c.customerId} className="cursor-pointer" onClick={() => navigate(`/customers/${c.customerId}`)}>
                      <Td className="pl-5 font-medium"><Link to={`/customers/${c.customerId}`}
                          onClick={(e) => e.stopPropagation()} className="hover:text-primary">#{c.customerId}</Link>
                        <span className="ml-2 text-xs text-muted-foreground">{c.city}</span></Td>
                      <Td>{segmentLabel(c.segment)}</Td>
                      <Td><RiskGradeBadge grade={c.riskGrade} /></Td>
                      <Td className="text-right font-medium text-negative">{formatPercent(c.pd)}</Td>
                      <Td className="text-right">{formatPercent(c.utilizationRate, 0)}</Td>
                      <Td className="pr-5 text-right">{formatMoney(c.totalDebt)}</Td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
            )}
          </CardContent>
        </Card>
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Segment özetleri</CardTitle>
            <CardDescription>Beklenen kayıp ve likidite</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {summary.isLoading ? <LoadingBlock rows={5} /> : s?.segments.map((seg) => (
              <div key={seg.segment} className="flex items-center justify-between gap-3 rounded-lg border p-3">
                <div>
                  <div className="text-sm font-medium">{segmentLabel(seg.segment)}</div>
                  <div className="text-xs text-muted-foreground">{formatNumber(seg.customers)} müşteri ·
                    tampon {formatNumber(seg.meanLiquidityBufferMonths, 1)} ay</div>
                </div>
                <div className="text-right">
                  <div className="text-sm font-semibold">{formatMoney(seg.expectedLoss, { compact: true })}</div>
                  <div className="text-xs text-muted-foreground">{formatNumber(seg.highRiskCustomers)} yüksek riskli</div>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <SectionTitle>Makro göstergeler</SectionTitle>
          <CardDescription>Aylık enflasyon, TCMB politika faizi ve USD/TRY (simülasyonun başlangıç ve geçmiş
            verisi)</CardDescription>
        </CardHeader>
        <CardContent>
          {macro.isLoading ? <ChartSkeleton /> : macro.isError
            ? <ErrorState error={macro.error} compact onRetry={() => macro.refetch()} />
            : <MacroChart data={macro.data ?? []} />}
        </CardContent>
      </Card>
    </div>
  );
}
