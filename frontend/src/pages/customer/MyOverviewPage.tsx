import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CreditCard, LineChart, PiggyBank, Sparkles, Wallet } from 'lucide-react';
import { ApiError } from '@/api/client';
import { twinsApi } from '@/api/endpoints';
import type { TwinFeatures } from '@/api/types';
import { useIdentity } from '@/auth/identity';
import { FanChart } from '@/components/charts';
import { ChartSkeleton, EmptyState, ErrorState, KpiCard, LoadingBlock, PageHeader } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/primitives';
import { formatDate, formatMoney, formatNumber, formatPercent } from '@/lib/format';
import { categoryLabel } from '@/lib/labels';

function SpendingBreakdown({ features }: { features: TwinFeatures }) {
  const rows = Object.entries(features.spendByCategory30d).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]);
  const total = rows.reduce((sum, [, v]) => sum + v, 0);
  if (!rows.length) return <EmptyState title="Son 30 günde harcama yok" />;
  const max = rows[0][1];
  return (
    <ul className="space-y-3">
      {rows.slice(0, 8).map(([category, amount], i) => (
        <li key={category} className="animate-fade-up" style={{ animationDelay: `${i * 40}ms` }}>
          <div className="mb-1 flex items-baseline justify-between text-sm">
            <span>{categoryLabel(category)}</span>
            <span className="tabular-nums"><b>{formatMoney(amount)}</b>
              <span className="ml-2 text-xs text-muted-foreground">{formatPercent(amount / total, 0)}</span></span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-muted">
            <div className="h-full rounded-full bg-chart-1 transition-[width] duration-700"
                 style={{ width: `${(amount / max) * 100}%`, backgroundColor: `hsl(var(--chart-${(i % 6) + 1}))` }} />
          </div>
        </li>
      ))}
    </ul>
  );
}

function trendText(trend: number | null): string {
  if (trend === null) return 'Karşılaştırma için yeterli veri yok';
  if (Math.abs(trend) < 0.05) return 'Önceki aylara benzer';
  return trend > 0 ? `Önceki aylardan %${formatNumber(trend * 100)} fazla` : `Önceki aylardan %${formatNumber(-trend * 100)} az`;
}

export function MyOverviewPage() {
  const { customerId } = useIdentity();
  const id = customerId ?? 0;
  const twin = useQuery({ queryKey: ['twin', id, null], queryFn: () => twinsApi.twin(id), enabled: id > 0 });
  const forecast = useQuery({ queryKey: ['forecast', id], queryFn: () => twinsApi.forecast(id), enabled: id > 0, retry: false });

  if (!id) return <EmptyState title="Müşteri seçilmedi" description="Üst menüden hangi müşteri olarak görüntülediğinizi seçin." />;
  if (twin.isLoading) return <><PageHeader title="Özetim" /><LoadingBlock rows={8} /></>;
  if (twin.error || !twin.data) {
    return <><PageHeader title="Özetim" /><ErrorState error={twin.error} onRetry={() => void twin.refetch()} /></>;
  }
  const f = twin.data.features;
  const liquid = f.checkingBalance + f.depositBalance;
  const f2 = forecast.data?.forecast;
  return (
    <>
      <PageHeader title="Merhaba 👋" eyebrow={`Müşteri ${id}`}
        description={`Hesaplarınızın ${formatDate(twin.data.asOf)} itibarıyla özeti.`}
        actions={<Button asChild><Link to="/me/coach"><Sparkles /> Finans koçuna sor</Link></Button>} />
      <div className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard label="Kullanılabilir param" icon={<Wallet />} value={formatMoney(liquid)}
          hint={`Vadesiz ${formatMoney(f.checkingBalance)} · birikim ${formatMoney(f.depositBalance)}`} />
        <KpiCard label="Son 30 gün harcama" icon={<LineChart />} value={formatMoney(f.spend30d)} hint={trendText(f.spendTrend)} />
        <KpiCard label="Kart borcu" icon={<CreditCard />} value={formatMoney(f.cardDebt)}
          tone={f.utilizationRate !== null && f.utilizationRate > 0.7 ? 'warning' : 'neutral'}
          hint={f.cardLimit > 0 ? `Limitin ${formatPercent(f.utilizationRate, 0)} kadarı kullanılıyor` : 'Kredi kartınız yok'} />
        <KpiCard label="Kaç ay idare eder?" icon={<PiggyBank />}
          tone={f.liquidityBufferMonths !== null && f.liquidityBufferMonths < 1 ? 'warning' : 'positive'}
          value={f.liquidityBufferMonths === null ? '–' : `${formatNumber(f.liquidityBufferMonths, 1)} ay`}
          hint="Gelir kesilse birikimin karşılayacağı süre" />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle className="text-base">Paranız nereye gidiyor?</CardTitle>
            <p className="text-xs text-muted-foreground">Son 30 günün harcamaları, kategoriye göre</p></CardHeader>
          <CardContent><SpendingBreakdown features={f} /></CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-base">Önümüzdeki aylarda bakiyeniz</CardTitle>
            <p className="text-xs text-muted-foreground">Bugünkü alışkanlıklarınızla olası bakiye aralığı</p></CardHeader>
          <CardContent>
            {forecast.isLoading ? <ChartSkeleton /> : forecast.error || !f2 ? (
              <EmptyState title="Tahmin henüz hazır değil" description={forecast.error instanceof ApiError
                && forecast.error.status === 404 ? 'Portföy simülasyonu çalıştırıldığında burada görünecek.'
                : 'Tahmin şu an alınamadı; daha sonra tekrar deneyin.'} />
            ) : (
              <>
                <FanChart data={f2.months.map((m) => ({ month: m.month, ...m.balance }))} />
                <p className="mt-3 text-sm text-muted-foreground">
                  En olası durumda {f2.months.length} ay sonra bakiyeniz yaklaşık{' '}
                  <b className="text-foreground">{formatMoney(f2.months[f2.months.length - 1]?.balance.p50)}</b> olur.
                  Açık renkli alan iyi ve kötü giden aylarla oluşabilecek aralığı gösterir.
                </p>
              </>
            )}
          </CardContent>
        </Card>
      </div>
      {f.monthlyObligations > 0 && (
        <Card className="mt-4 p-5 text-sm">
          Her ay düzenli ödemeleriniz (kredi taksitleri ve kart asgari ödemesi) yaklaşık{' '}
          <b>{formatMoney(f.monthlyObligations)}</b>
          {f.incomeEstimate ? <> — gelirinizin {formatPercent(f.monthlyObligations / f.incomeEstimate, 0)} kadarı.</> : '.'}
        </Card>
      )}
    </>
  );
}
