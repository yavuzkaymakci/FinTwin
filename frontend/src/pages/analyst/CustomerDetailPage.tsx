import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft, CalendarClock, CreditCard, History, Landmark, PiggyBank, RefreshCw, Rewind, ShieldAlert, TrendingUp,
  Wallet,
} from 'lucide-react';
import { customersApi, twinsApi } from '@/api/endpoints';
import type { RiskReason, TwinFeatures, TwinView } from '@/api/types';
import { FanChart, FeatureHistoryChart } from '@/components/charts';
import {
  ChartSkeleton, DeltaBadge, EmptyState, ErrorState, LoadingBlock, PageHeader, RiskGradeBadge, Spinner,
} from '@/components/common';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Input, Table, Td, Th, Tr } from '@/components/ui/primitives';
import { Select, Slider, Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/radix';
import { ApiError } from '@/api/client';
import {
  formatDate, formatDateTime, formatMoney, formatNumber, formatPercent, formatPoints,
} from '@/lib/format';
import { accountLabel, HISTORY_FEATURES, loanLabel, segmentLabel, statusLabel } from '@/lib/labels';
import { gradeBand } from '@/lib/risk';
import { cn } from '@/lib/utils';

const DAY = 86_400_000;

function isoDay(d: Date): string {
  return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
}

interface FeatureCardSpec {
  key: keyof TwinFeatures;
  label: string;
  icon: React.ReactNode;
  format: (v: number | null) => string;
  higherIsWorse: boolean;
  delta: (v: number) => string;
}

const FEATURE_CARDS: FeatureCardSpec[] = [
  { key: 'liquidityBufferMonths', label: 'Likidite tamponu', icon: <PiggyBank />, higherIsWorse: false,
    format: (v) => (v === null ? '–' : `${formatNumber(Math.min(v, 60), 1)} ay`), delta: (v) => `${formatNumber(v, 1)} ay` },
  { key: 'utilizationRate', label: 'Kart limit kullanımı', icon: <CreditCard />, higherIsWorse: true,
    format: (v) => formatPercent(v, 0), delta: (v) => formatPoints(v, 0) },
  { key: 'debtToIncome', label: 'Borç ödemesi / gelir', icon: <ShieldAlert />, higherIsWorse: true,
    format: (v) => formatPercent(v, 0), delta: (v) => formatPoints(v, 0) },
  { key: 'incomeEstimate', label: 'Tahmini aylık gelir', icon: <TrendingUp />, higherIsWorse: false,
    format: (v) => formatMoney(v), delta: (v) => formatMoney(v, { compact: true }) },
  { key: 'avgMonthlyExpense', label: 'Ortalama aylık gider', icon: <Wallet />, higherIsWorse: true,
    format: (v) => formatMoney(v), delta: (v) => formatMoney(v, { compact: true }) },
  { key: 'cardDebt', label: 'Kart borcu', icon: <CreditCard />, higherIsWorse: true,
    format: (v) => formatMoney(v), delta: (v) => formatMoney(v, { compact: true }) },
  { key: 'checkingBalance', label: 'Vadesiz bakiye', icon: <Landmark />, higherIsWorse: false,
    format: (v) => formatMoney(v), delta: (v) => formatMoney(v, { compact: true }) },
  { key: 'latePaymentCount', label: 'Gecikmeli ödeme', icon: <History />, higherIsWorse: true,
    format: (v) => formatNumber(v), delta: (v) => formatNumber(v) },
];

function num(f: TwinFeatures | undefined, key: keyof TwinFeatures): number | null {
  const v = f?.[key];
  return typeof v === 'number' ? v : v === null || v === undefined ? null : Number(v);
}

function FeatureCards({ twin, current }: { twin: TwinView; current?: TwinView }) {
  const comparing = current && current.asOf !== twin.asOf;
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      {FEATURE_CARDS.map((spec) => {
        const value = num(twin.features, spec.key);
        const now = comparing ? num(current.features, spec.key) : null;
        return (
          <div key={spec.key} className="rounded-lg border bg-card p-3.5 transition-colors">
            <div className="flex items-center gap-2 text-xs text-muted-foreground [&_svg]:size-3.5">
              {spec.icon}{spec.label}
            </div>
            <div className="mt-1 text-lg font-semibold tracking-tight">{spec.format(value)}</div>
            {comparing && value !== null && now !== null && (
              <div className="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[11px] text-muted-foreground">
                <span className="whitespace-nowrap">bugün {spec.format(now)}</span>
                <DeltaBadge value={now - value} format={spec.delta} higherIsWorse={spec.higherIsWorse} />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function ReasonBars({ reasons }: { reasons: RiskReason[] }) {
  if (!reasons.length) return <EmptyState title="Riski artıran belirgin bir etken yok" />;
  const max = Math.max(...reasons.map((r) => Math.abs(r.impact)), 0.01);
  return (
    <ul className="space-y-3">
      {reasons.map((r) => (
        <li key={r.code} className="space-y-1">
          <div className="flex items-start justify-between gap-3 text-sm">
            <span>{r.textTr}</span>
            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">+{formatNumber(r.impact, 2)}</span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-muted">
            <div className="h-full rounded-full bg-negative/80 transition-[width] duration-700"
                 style={{ width: `${(Math.abs(r.impact) / max) * 100}%` }} />
          </div>
        </li>
      ))}
      <li className="text-[11px] text-muted-foreground">Çubuklar SHAP etkisini (log-odds) gösterir: uzun çubuk = skoru
        daha çok artıran etken.</li>
    </ul>
  );
}

function RiskCard({ customerId }: { customerId: number }) {
  const client = useQueryClient();
  const risk = useQuery({ queryKey: ['risk', customerId], queryFn: () => twinsApi.risk(customerId), retry: false });
  const refresh = useMutation({
    mutationFn: () => twinsApi.risk(customerId, true),
    onSuccess: (data) => client.setQueryData(['risk', customerId], data),
  });
  const r = risk.data;
  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between space-y-0">
        <div>
          <CardTitle>Risk skoru</CardTitle>
          <CardDescription>{r ? `${formatDateTime(r.scoredAt)} · model ${r.pdModelVersion}` : 'PD, churn ve nedenleri'}
          </CardDescription>
        </div>
        <Button size="sm" variant="outline" onClick={() => refresh.mutate()} disabled={refresh.isPending}>
          {refresh.isPending ? <Spinner /> : <RefreshCw />} Yeniden skorla
        </Button>
      </CardHeader>
      <CardContent>
        {refresh.isError && <div className="mb-3"><ErrorState compact error={refresh.error}
          title={refresh.error instanceof ApiError && refresh.error.code === 'ML_SERVICE_UNAVAILABLE'
            ? 'ML servisi şu an kullanılamıyor; son kayıtlı skor gösteriliyor' : 'Skorlama başarısız'} /></div>}
        {risk.isLoading ? <LoadingBlock rows={5} /> : risk.isError ? (
          risk.error instanceof ApiError && risk.error.status === 404
            ? <EmptyState title="Bu müşteri için henüz skor yok" description="'Yeniden skorla' ile hesaplayabilirsiniz." />
            : <ErrorState error={risk.error} compact />
        ) : r && (
          <div className="space-y-5">
            <div className="grid grid-cols-3 gap-3">
              <div className="rounded-lg bg-muted/60 p-3">
                <div className="text-xs text-muted-foreground">6 aylık PD</div>
                <div className="text-xl font-semibold">{formatPercent(r.pd)}</div>
              </div>
              <div className="rounded-lg bg-muted/60 p-3">
                <div className="text-xs text-muted-foreground">Churn</div>
                <div className="text-xl font-semibold">{formatPercent(r.churn)}</div>
              </div>
              <div className="rounded-lg bg-muted/60 p-3">
                <div className="text-xs text-muted-foreground">Derece</div>
                <div className="mt-1 flex items-center gap-2"><RiskGradeBadge grade={r.riskGrade} />
                  <span className="text-xs font-medium">{gradeBand(r.riskGrade).label}</span></div>
              </div>
            </div>
            <Tabs defaultValue="pd">
              <TabsList><TabsTrigger value="pd">Temerrüt nedenleri</TabsTrigger>
                <TabsTrigger value="churn">Ayrılma nedenleri</TabsTrigger></TabsList>
              <TabsContent value="pd"><ReasonBars reasons={r.reasons.pd ?? []} /></TabsContent>
              <TabsContent value="churn"><ReasonBars reasons={r.reasons.churn ?? []} /></TabsContent>
            </Tabs>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function TimeTravel({ customerId, current, firstDay, onChange, asOf }: {
  customerId: number; current: TwinView; firstDay: string; asOf: string | null; onChange: (d: string | null) => void;
}) {
  const end = new Date(current.lastEventAt);
  const start = new Date(`${firstDay}T00:00:00`);
  const totalDays = Math.max(1, Math.round((end.getTime() - start.getTime()) / DAY));
  const selected = asOf ? new Date(`${asOf}T00:00:00`) : end;
  const offset = Math.round((selected.getTime() - start.getTime()) / DAY);
  const [draft, setDraft] = useState<number | null>(null);
  const shown = draft ?? offset;
  const shownDate = new Date(start.getTime() + shown * DAY);
  const commit = (days: number) => {
    setDraft(null);
    onChange(days >= totalDays ? null : isoDay(new Date(start.getTime() + days * DAY)));
  };
  return (
    <Card className="overflow-hidden border-primary/30">
      <div className="bg-gradient-to-r from-accent/80 via-accent/30 to-transparent px-5 py-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-start gap-3">
            <div className="rounded-lg bg-primary p-2 text-primary-foreground"><Rewind className="size-5" /></div>
            <div>
              <div className="font-semibold">Zaman yolculuğu</div>
              <div className="text-xs text-muted-foreground">İkizin geçmiş bir gündeki hâli, event store'daki olaylar o
                güne kadar yeniden oynatılarak hesaplanır (event sourcing).</div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Input type="date" className="h-8 w-40" value={isoDay(shownDate)} min={firstDay} max={isoDay(end)}
                   aria-label="Tarih seç" onChange={(e) => e.target.value && onChange(e.target.value >= isoDay(end)
                     ? null : e.target.value)} />
            <Button size="sm" variant={asOf ? 'default' : 'outline'} onClick={() => onChange(null)}>Bugün</Button>
          </div>
        </div>
        <div className="mt-4">
          <Slider min={0} max={totalDays} step={1} value={[shown]} aria-label="Zaman yolculuğu tarihi"
                  onValueChange={([v]) => setDraft(v)} onValueCommit={([v]) => commit(v)} />
          <div className="mt-2 flex justify-between text-[11px] text-muted-foreground">
            <span>{formatDate(firstDay)}</span>
            <span className="font-medium text-foreground">{formatDate(shownDate)}</span>
            <span>{formatDate(end)}</span>
          </div>
        </div>
      </div>
      <TwinAsOfStatus customerId={customerId} asOf={asOf} />
    </Card>
  );
}

function TwinAsOfStatus({ customerId, asOf }: { customerId: number; asOf: string | null }) {
  const twin = useQuery({ queryKey: ['twin', customerId, asOf], queryFn: () => twinsApi.twin(customerId, asOf ?? undefined) });
  if (!twin.data) return <div className="px-5 py-2 text-xs text-muted-foreground">{twin.isError ? 'Bu tarihte ikiz yok.'
    : 'Hesaplanıyor…'}</div>;
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t px-5 py-2 text-xs text-muted-foreground">
      <span className="flex items-center gap-1.5"><CalendarClock className="size-3.5" />
        Değerlendirme anı: <strong className="font-medium text-foreground">{formatDateTime(twin.data.asOf)}</strong></span>
      <span>Kaynak: <Badge tone={twin.data.source === 'REPLAYED' ? 'primary' : 'neutral'}>
        {twin.data.source === 'REPLAYED' ? 'Snapshot + olay tekrarı' : 'Güncel projeksiyon'}</Badge></span>
      <span>Son uygulanan olay sırası: <strong className="font-medium text-foreground">
        {formatNumber(twin.data.lastSequence)}</strong></span>
    </div>
  );
}

function HistoryCard({ customerId, asOf }: { customerId: number; asOf: string | null }) {
  const [feature, setFeature] = useState(HISTORY_FEATURES[0].key);
  const spec = HISTORY_FEATURES.find((f) => f.key === feature) ?? HISTORY_FEATURES[0];
  const history = useQuery({ queryKey: ['history', customerId, feature], queryFn: () => twinsApi.history(customerId, feature) });
  const points = (history.data?.points ?? []).map((p) => ({ date: p.date, value: p.value === null ? null
    : Math.min(Number(p.value), spec.kind === 'months' ? 60 : Number.MAX_VALUE) }));
  const highlight = asOf ? points.reduce<string | null>((best, p) => (p.date <= asOf ? p.date : best), null) : null;
  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between space-y-0">
        <div><CardTitle>Özellik geçmişi</CardTitle>
          <CardDescription>Aylık snapshot'lar ve güncel değer</CardDescription></div>
        <Select ariaLabel="Özellik" className="w-56" value={feature} onValueChange={setFeature}
                options={HISTORY_FEATURES.map((f) => ({ value: f.key, label: f.label }))} />
      </CardHeader>
      <CardContent>{history.isLoading ? <ChartSkeleton height={220} /> : history.isError
        ? <ErrorState error={history.error} compact /> : <FeatureHistoryChart data={points} kind={spec.kind}
          highlight={highlight} />}</CardContent>
    </Card>
  );
}

function ForecastCard({ customerId }: { customerId: number }) {
  const forecast = useQuery({ queryKey: ['forecast', customerId], queryFn: () => twinsApi.forecast(customerId), retry: false });
  const [metric, setMetric] = useState<'balance' | 'buffer'>('balance');
  const f = forecast.data;
  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between space-y-0">
        <div><CardTitle>Nakit akışı tahmini</CardTitle>
          <CardDescription>{f ? `${f.scenarioName} senaryosu · koşu #${f.runId}` : 'Simülasyondan P10 / P50 / P90 bandı'}
          </CardDescription></div>
        <Tabs value={metric} onValueChange={(v) => setMetric(v as 'balance' | 'buffer')}>
          <TabsList><TabsTrigger value="balance">Bakiye</TabsTrigger><TabsTrigger value="buffer">Tampon</TabsTrigger></TabsList>
        </Tabs>
      </CardHeader>
      <CardContent>
        {forecast.isLoading ? <ChartSkeleton /> : forecast.isError ? (
          <EmptyState title="Bu müşteri için tahmin yok"
            description={<>Bir senaryo koşusu tamamlandığında burada görünür. <Link className="text-primary hover:underline"
              to="/scenarios">Senaryolar</Link></>} />
        ) : f && (
          <>
            <FanChart label={metric === 'balance' ? 'Bakiye' : 'Tampon'}
              valueFormat={metric === 'buffer' ? (v) => `${formatNumber(v, 1)} ay` : undefined}
              data={f.forecast.months.map((m) => ({ month: m.month,
                ...(metric === 'balance' ? m.balance : m.liquidityBufferMonths) }))} />
            <div className="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs text-muted-foreground">
              <span>Başlangıç bakiyesi: <strong className="text-foreground">{formatMoney(f.forecast.startBalance)}</strong></span>
              <span>PD: {formatPercent(f.forecast.pdStart)} → <strong className="text-foreground">
                {formatPercent(f.forecast.pdHorizon)}</strong></span>
              <span>Ufukta temerrüt olasılığı: <strong className="text-foreground">
                {formatPercent(f.forecast.defaultProbability)}</strong></span>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}

function TransactionsCard({ customerId, until }: { customerId: number; until: string }) {
  const to = new Date(`${until}T00:00:00`);
  const from = isoDay(new Date(to.getTime() - 45 * DAY));
  const toDay = isoDay(new Date(to.getTime() + DAY));
  const tx = useQuery({ queryKey: ['transactions', customerId, from, toDay],
    queryFn: () => customersApi.transactions(customerId, from, toDay, 15) });
  const rows = [...(tx.data?.content ?? [])].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));
  return (
    <Card>
      <CardHeader><CardTitle>Son işlemler</CardTitle>
        <CardDescription>{formatDate(from)} – {formatDate(until)} arası (çekirdek bankacılık kayıtları)</CardDescription>
      </CardHeader>
      <CardContent className="px-0 pb-2">
        {tx.isLoading ? <LoadingBlock className="px-5" rows={6} /> : tx.isError
          ? <div className="px-5"><ErrorState error={tx.error} compact /></div>
          : !rows.length ? <div className="px-5"><EmptyState title="Bu aralıkta işlem yok" /></div> : (
            <Table>
              <thead><tr className="border-b"><Th className="pl-5">Tarih</Th><Th>Açıklama</Th><Th>Kanal</Th>
                <Th className="pr-5 text-right">Tutar</Th></tr></thead>
              <tbody>{rows.map((t) => (
                <Tr key={t.id}>
                  <Td className="pl-5 text-xs text-muted-foreground">{formatDateTime(t.occurredAt)}</Td>
                  <Td className="max-w-[280px] truncate font-mono text-xs" title={t.rawDescription}>{t.rawDescription}</Td>
                  <Td className="text-xs">{t.channel}</Td>
                  <Td className={cn('pr-5 text-right font-medium', t.direction === 'CREDIT' ? 'text-positive' : '')}>
                    {t.direction === 'CREDIT' ? '+' : '−'}{t.currency === 'TRY' ? formatMoney(t.amount, { digits: 2 })
                      : `${formatNumber(t.amount, 2)} ${t.currency}`}</Td>
                </Tr>))}
              </tbody>
            </Table>
          )}
      </CardContent>
    </Card>
  );
}

export function CustomerDetailPage() {
  const id = Number(useParams().id);
  const [asOf, setAsOf] = useState<string | null>(null);
  const detail = useQuery({ queryKey: ['customer', id], queryFn: () => customersApi.detail(id) });
  const current = useQuery({ queryKey: ['twin', id, null], queryFn: () => twinsApi.twin(id) });
  const atDate = useQuery({ queryKey: ['twin', id, asOf], queryFn: () => twinsApi.twin(id, asOf ?? undefined),
    enabled: asOf !== null, placeholderData: (prev) => prev });
  const shown = asOf ? atDate.data ?? current.data : current.data;
  const c = detail.data?.customer;
  const firstDay = useMemo(() => {
    const since = c?.customerSince ?? '2024-10-01';
    return since < '2024-10-01' ? '2024-10-01' : since;
  }, [c?.customerSince]);

  if (current.isError) {
    return <div><BackLink /><ErrorState error={current.error} title="İkiz bulunamadı" onRetry={() => current.refetch()} /></div>;
  }
  return (
    <div className="space-y-5">
      <BackLink />
      <PageHeader eyebrow="Müşteri ikizi" title={`Müşteri #${id}`}
        description={c ? `${segmentLabel(c.segment)} · ${c.city} · ${c.age} yaş · ${formatDate(c.customerSince)}'den beri
          müşteri · ${statusLabel(c.status)}` : undefined}
        actions={shown && <Badge tone="outline">{formatNumber(shown.lastSequence)} olay</Badge>} />

      {current.data ? <TimeTravel customerId={id} current={current.data} firstDay={firstDay} asOf={asOf}
                                  onChange={setAsOf} /> : <LoadingBlock rows={2} />}

      <div className="grid gap-5 xl:grid-cols-5">
        <div className="space-y-5 xl:col-span-3">
          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <div><CardTitle>İkiz özellikleri</CardTitle>
                <CardDescription>{asOf ? `${formatDate(asOf)} itibarıyla · bugünkü değerlerle karşılaştırmalı`
                  : 'Güncel projeksiyon'}</CardDescription></div>
              {atDate.isFetching && <Spinner className="text-muted-foreground" />}
            </CardHeader>
            <CardContent>{shown ? <FeatureCards twin={shown} current={current.data} /> : <LoadingBlock rows={4} />}</CardContent>
          </Card>
          <HistoryCard customerId={id} asOf={asOf} />
        </div>
        <div className="space-y-5 xl:col-span-2">
          <RiskCard customerId={id} />
          <Card>
            <CardHeader><CardTitle>Hesaplar ve krediler</CardTitle>
              <CardDescription>{asOf ? `${formatDate(asOf)} itibarıyla ikizdeki bakiyeler` : 'İkizdeki güncel bakiyeler'}
              </CardDescription></CardHeader>
            <CardContent className="space-y-2">
              {!shown ? <LoadingBlock rows={3} /> : (
                <>
                  {shown.accounts.filter((a) => !a.closed).map((a) => (
                    <div key={a.accountId} className="flex items-center justify-between rounded-md border px-3 py-2 text-sm">
                      <span>{accountLabel(a.type, a.currency)}</span>
                      <span className="font-medium">{a.type === 'CREDIT_CARD'
                        ? <>{formatMoney(a.balance)} <span className="text-xs text-muted-foreground">/ {formatMoney(a.creditLimit)}</span></>
                        : a.currency === 'TRY' ? formatMoney(a.balance) : `${formatNumber(a.balance, 2)} ${a.currency}`}</span>
                    </div>
                  ))}
                  {shown.loans.filter((l) => l.active).map((l) => (
                    <div key={l.loanId} className="flex items-center justify-between rounded-md border px-3 py-2 text-sm">
                      <span>{loanLabel(l.type)} <span className="text-xs text-muted-foreground">· {l.remainingInstallments} taksit
                        kaldı</span></span>
                      <span className="font-medium">{formatMoney(l.outstanding)}</span>
                    </div>
                  ))}
                </>
              )}
            </CardContent>
          </Card>
        </div>
      </div>

      <ForecastCard customerId={id} />
      {shown && <TransactionsCard customerId={id} until={(asOf ?? shown.lastEventAt).slice(0, 10)} />}
    </div>
  );
}

function BackLink() {
  return (
    <Link to="/customers" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
      <ArrowLeft className="size-4" /> Müşteriler
    </Link>
  );
}
