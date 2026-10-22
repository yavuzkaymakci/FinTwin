import { Activity, BrainCircuit, Cpu, ExternalLink, ShieldCheck, Sparkles } from 'lucide-react';
import { ErrorState, KpiCard, LoadingBlock, PageHeader, SectionTitle } from '@/components/common';
import { Badge, Card, CardContent, CardHeader, CardTitle, Table, Td, Th, Tr } from '@/components/ui/primitives';
import { formatDateTime, formatNumber, formatPercent } from '@/lib/format';
import { mlDegraded, useSystemStatus } from '@/lib/queries';
import type { SystemStatus } from '@/api/types';

const MODEL_TITLES: Record<string, { title: string; description: string }> = {
  pd: { title: 'Temerrüt olasılığı (PD)', description: '6 ay içinde temerrüde düşme olasılığı, LightGBM + isotonic kalibrasyon' },
  churn: { title: 'Ayrılma (churn)', description: '6 ay içinde bankadan ayrılma olasılığı' },
  nlp: { title: 'İşlem kategorilendirme', description: 'Ham işlem açıklamasından harcama kategorisi (TF-IDF + doğrusal model)' },
};

type Model = SystemStatus['ml']['models'][number];

function num(summary: Record<string, unknown>, key: string): number | null {
  const v = summary[key];
  return typeof v === 'number' ? v : null;
}

function Metric({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-md bg-muted/60 p-3">
      <div className="text-[11px] text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold tabular-nums">{value}</div>
      {hint && <div className="text-[11px] text-muted-foreground">{hint}</div>}
    </div>
  );
}

function ModelCard({ model }: { model: Model }) {
  const meta = MODEL_TITLES[model.name] ?? { title: model.name, description: '' };
  const s = model.summary ?? {};
  const f = (key: string, digits = 3) => {
    const v = num(s, key);
    return v === null ? '–' : formatNumber(v, digits);
  };
  return (
    <Card className="animate-fade-up">
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-2">
          <div>
            <CardTitle className="text-base">{meta.title}</CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">{meta.description}</p>
          </div>
          <Badge tone="outline" className="font-mono">{model.version}</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {model.name === 'nlp' ? (
          <div className="grid grid-cols-2 gap-2">
            <Metric label="Macro F1 (test)" value={f('testMacroF1')} />
            <Metric label="Macro F1 (görülmemiş işyerleri)" value={f('heldOutMerchantsMacroF1')} />
          </div>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Metric label="AUC" value={f('testAuc')} />
              <Metric label="Gini" value={f('testGini')} />
              <Metric label="KS" value={f('testKs')} />
              <Metric label="Brier" value={f('testBrier', 4)} />
            </div>
            <div className="rounded-md border p-3 text-xs">
              <div className="mb-1 font-medium">Kalibrasyon (test dönemi)</div>
              <div className="flex flex-wrap gap-x-6 gap-y-1 text-muted-foreground">
                <span>Ortalama tahmin <b className="text-foreground">{formatPercent(num(s, 'meanPredicted'))}</b></span>
                <span>Gerçekleşen oran <b className="text-foreground">{formatPercent(num(s, 'observedRate'))}</b></span>
              </div>
            </div>
          </>
        )}
        <div className="text-[11px] text-muted-foreground">Eğitim: {formatDateTime(model.trainedAt)}
          {Array.isArray(s.features) && ` · ${s.features.length} özellik`} · metrikler out-of-time test dönemine aittir</div>
      </CardContent>
    </Card>
  );
}

export function SystemPage() {
  const status = useSystemStatus();
  if (status.isLoading) return <><PageHeader title="Sistem ve modeller" /><LoadingBlock rows={6} /></>;
  if (status.error || !status.data) {
    return <><PageHeader title="Sistem ve modeller" /><ErrorState error={status.error} onRetry={() => void status.refetch()} /></>;
  }
  const s = status.data;
  const degraded = mlDegraded(s);
  const breakerTone = s.ml.circuitBreaker === 'CLOSED' ? 'positive' : s.ml.circuitBreaker === 'OPEN' ? 'negative' : 'warning';
  return (
    <>
      <PageHeader title="Sistem ve modeller" eyebrow="Operasyon"
        description="Aktif model sürümleri, ML servisi ve devre kesici durumu, yapay zekâ sağlayıcısı."
        actions={<a href="/swagger-ui/index.html" target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm hover:bg-muted">
          API dokümantasyonu <ExternalLink className="size-3.5" /></a>} />
      <div className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard label="ML servisi" icon={<Cpu />} tone={degraded ? 'negative' : 'positive'}
          value={s.ml.reachable ? 'Çalışıyor' : 'Ulaşılamıyor'} hint={s.ml.error ?? 'http://localhost:8000'} />
        <KpiCard label="Devre kesici" icon={<ShieldCheck />} tone={breakerTone}
          value={s.ml.circuitBreaker} hint={breakerTone === 'positive' ? 'İstekler normal akıyor'
            : 'ML çağrıları geçici olarak kesildi; kayıtlı skorlar gösterilir'} />
        <KpiCard label="Yapay zekâ sağlayıcısı" icon={<Sparkles />} tone={s.ai.configured ? 'positive' : 'warning'}
          value={s.ai.demo ? 'Demo modu' : s.ai.configured ? s.ai.provider : 'Yapılandırılmadı'}
          hint={s.ai.demo ? 'Yanıtlar önceden hazırlanmış, araç sonuçları gerçek' : s.ai.model ?? 'Sohbet ekranları kapalı'} />
        <KpiCard label="Veri akışı" icon={<Activity />} value={s.replay.mode}
          hint={s.replay.simulatedTime ? `Simüle tarih ${formatDateTime(s.replay.simulatedTime)}` : 'Replay boşta'} />
      </div>
      <SectionTitle>Aktif modeller</SectionTitle>
      {!s.ml.models.length ? (
        <Card className="p-6 text-sm text-muted-foreground"><BrainCircuit className="mb-2 size-5" />
          Model bilgisi alınamadı. ML servisi açıldığında burada sürümler ve metrikler görünür.</Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          {['pd', 'churn', 'nlp'].map((n) => s.ml.models.find((m) => m.name === n))
            .filter((m): m is Model => m !== undefined).map((m) => <ModelCard key={m.name} model={m} />)}
        </div>
      )}
      {s.ml.models.some((m) => Array.isArray(m.summary?.features)) && (
        <Card className="mt-6">
          <CardHeader><CardTitle className="text-base">Model girdileri</CardTitle></CardHeader>
          <CardContent>
            <Table>
              <thead><tr><Th>Model</Th><Th>Özellikler</Th></tr></thead>
              <tbody>
                {s.ml.models.filter((m) => Array.isArray(m.summary?.features)).map((m) => (
                  <Tr key={m.name}>
                    <Td className="font-medium">{MODEL_TITLES[m.name]?.title ?? m.name}</Td>
                    <Td className="font-mono text-[11px] text-muted-foreground">{(m.summary.features as string[]).join(', ')}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          </CardContent>
        </Card>
      )}
      <p className="mt-4 text-[11px] text-muted-foreground">Son kontrol: {formatDateTime(s.time)} · 15 saniyede bir yenilenir</p>
    </>
  );
}
