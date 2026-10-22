import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { GitCompare, Play, Plus, Sparkles } from 'lucide-react';
import { ApiError } from '@/api/client';
import { scenariosApi } from '@/api/endpoints';
import type { Scenario, SimulationRun } from '@/api/types';
import { EmptyState, ErrorState, LoadingBlock, PageHeader, Spinner } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Input, Label, Progress, Table, Td,
  Textarea, Th, Tr } from '@/components/ui/primitives';
import { Dialog, DialogContent, Select } from '@/components/ui/radix';
import { formatDateTime, formatNumber } from '@/lib/format';
import { statusLabel } from '@/lib/labels';
import { cn } from '@/lib/utils';
import { EMPTY_FORM, type FormErrors, type ScenarioForm, validateScenario } from './scenarioForm';

export function parameterChips(s: Scenario['parameters']): string[] {
  const inflation = s.monthlyInflationPct.map((v) => `%${formatNumber(v, 1)}`);
  return [
    `USD/TRY ${s.usdTryChangePct >= 0 ? '+' : ''}%${formatNumber(s.usdTryChangePct, 0)}`,
    `Faiz ${s.policyRateChangeBp >= 0 ? '+' : ''}${formatNumber(s.policyRateChangeBp)} bp`,
    `Enflasyon ${inflation.length > 3 ? `${inflation.slice(0, 3).join(' · ')} …` : inflation.join(' · ')}`,
    `Ücret %${formatNumber(s.wageIndexationPct, 0)} / ${s.wageAdjustmentLagMonths} ay`,
    `${s.horizonMonths} ay · ${s.monteCarloPaths} yol`,
    s.shockProfile === 'IMMEDIATE' ? 'Ani şok' : `Kademeli (${s.shockRampMonths} ay)`,
  ];
}

export function RunStatusBadge({ run }: { run: Pick<SimulationRun, 'status' | 'progress'> }) {
  const tone = run.status === 'SUCCEEDED' ? 'positive' : run.status === 'FAILED' ? 'negative'
    : run.status === 'RUNNING' ? 'info' : 'neutral';
  return (
    <Badge tone={tone}>
      {(run.status === 'RUNNING' || run.status === 'QUEUED' || run.status === 'PENDING') && (
        <span className="size-1.5 animate-pulse-dot rounded-full bg-current" />)}
      {statusLabel(run.status)}{run.status === 'RUNNING' ? ` %${formatNumber(run.progress)}` : ''}
    </Badge>
  );
}

function Field({ label, error, children, hint }: { label: string; error?: string; children: React.ReactNode;
  hint?: string }) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      {children}
      {error ? <p className="text-xs text-negative" role="alert">{error}</p>
        : hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

export function NewScenarioForm({ onCreated }: { onCreated: (s: Scenario) => void }) {
  const [form, setForm] = useState<ScenarioForm>(EMPTY_FORM);
  const [errors, setErrors] = useState<FormErrors>({});
  const client = useQueryClient();
  const create = useMutation({
    mutationFn: scenariosApi.create,
    onSuccess: (s) => {
      client.invalidateQueries({ queryKey: ['scenarios'] });
      onCreated(s);
    },
  });
  const set = (key: keyof ScenarioForm) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const { errors: found, request } = validateScenario(form);
    setErrors(found);
    if (request) create.mutate(request);
  };
  const field = (key: keyof ScenarioForm, label: string, hint?: string, props: React.InputHTMLAttributes<HTMLInputElement> = {}) => (
    <Field label={label} error={errors[key]} hint={hint}>
      <Input value={form[key] as string} onChange={set(key)} aria-invalid={!!errors[key]} aria-label={label} {...props} />
    </Field>
  );
  return (
    <form onSubmit={submit} className="space-y-5" noValidate>
      <div className="grid gap-4 sm:grid-cols-2">
        {field('code', 'Kod', 'Örn. USD40_INF4')}
        {field('name', 'Ad', 'Örn. Dolar +%40, enflasyon %4')}
      </div>
      <Field label="Açıklama"><Textarea rows={2} value={form.description} onChange={set('description')}
                                        aria-label="Açıklama" /></Field>
      <div className="grid gap-4 sm:grid-cols-3">
        {field('usdTryChangePct', 'USD/TRY değişimi (%)', '−50 … 200; 30 = kur ×1,30', { inputMode: 'decimal' })}
        {field('policyRateChangeBp', 'Politika faizi (baz puan)', '−3000 … 3000; 500 = +5 puan', { inputMode: 'numeric' })}
        {field('horizonMonths', 'Ufuk (ay)', '1 … 36', { inputMode: 'numeric' })}
      </div>
      {field('monthlyInflationPct', 'Aylık enflasyon patikası (%)', 'Boşlukla ayırın, örn. "4 4 4 3,5 3 2"; son değer ufka '
        + 'kadar tekrarlanır')}
      <div className="grid gap-4 sm:grid-cols-3">
        {field('wageIndexationPct', 'Ücret ayarlaması (% enflasyon)', '0 … 150', { inputMode: 'decimal' })}
        {field('wageAdjustmentLagMonths', 'Zam aralığı (ay)', '6 = Ocak ve Temmuz; 0–1 = aylık', { inputMode: 'numeric' })}
        <Field label="Şok profili">
          <Select ariaLabel="Şok profili" value={form.shockProfile} onValueChange={(v) => setForm((f) => ({ ...f,
            shockProfile: v as ScenarioForm['shockProfile'] }))} options={[{ value: 'IMMEDIATE', label: 'Ani' },
            { value: 'GRADUAL', label: 'Kademeli' }]} />
        </Field>
      </div>
      <div className="grid gap-4 sm:grid-cols-3">
        {field('shockRampMonths', 'Kademeli geçiş (ay)', '1 … 36', { inputMode: 'numeric', disabled: form.shockProfile !== 'GRADUAL' })}
        {field('monteCarloPaths', 'Monte Carlo yol sayısı', '10 … 2000', { inputMode: 'numeric' })}
        {field('seed', 'Rastgelelik tohumu', 'Aynı tohum = aynı sonuç', { inputMode: 'numeric' })}
      </div>
      {create.isError && <ErrorState compact error={create.error} title={create.error instanceof ApiError
        && create.error.status === 409 ? 'Bu kodla bir senaryo zaten var' : 'Senaryo kaydedilemedi'} />}
      <div className="flex justify-end gap-2">
        <Button type="submit" disabled={create.isPending}>{create.isPending ? <Spinner /> : <Plus />} Senaryoyu kaydet</Button>
      </div>
    </form>
  );
}

function RunDialog({ scenario, scenarios, onClose }: { scenario: Scenario; scenarios: Scenario[]; onClose: () => void }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const base = scenarios.find((s) => s.code === 'BASE');
  const [baseline, setBaseline] = useState(scenario.code === 'BASE' ? 'none' : String(base?.id ?? 'none'));
  const [start, setStart] = useState('current');
  const run = useMutation({
    mutationFn: () => scenariosApi.run(scenario.id, { baselineScenarioId: baseline === 'none' ? null : Number(baseline),
      startDate: start === 'current' ? null : start }),
    onSuccess: (r) => {
      client.invalidateQueries({ queryKey: ['runs'] });
      navigate(`/runs/${r.id}`);
    },
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`${scenario.name} — simülasyon başlat`}
                     description="Koşu ML servisinde arka planda çalışır (200 yolla yaklaşık 2–4 dakika).">
        <div className="space-y-4">
          <div className="flex flex-wrap gap-1.5">{parameterChips(scenario.parameters).map((c) =>
            <Badge key={c} tone="outline">{c}</Badge>)}</div>
          <Field label="Karşılaştırma (baz) senaryosu">
            <Select ariaLabel="Baz senaryo" value={baseline} onValueChange={setBaseline} options={[
              { value: 'none', label: 'Yok' }, ...scenarios.filter((s) => s.id !== scenario.id)
                .map((s) => ({ value: String(s.id), label: s.name }))]} />
          </Field>
          <Field label="Başlangıç noktası" hint="Geçmiş bir aylık snapshot'tan başlatmak geriye dönük test içindir.">
            <Select ariaLabel="Başlangıç" value={start} onValueChange={setStart} options={[
              { value: 'current', label: 'Güncel portföy' },
              ...['2026-04-01', '2026-03-01', '2025-12-01', '2025-09-01'].map((d) => ({ value: d, label: `${d} snapshot'ı` }))]} />
          </Field>
          {run.isError && <ErrorState compact error={run.error} title={run.error instanceof ApiError
            && run.error.code === 'ML_SERVICE_UNAVAILABLE' ? 'ML servisi kullanılamıyor' : 'Koşu başlatılamadı'} />}
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={onClose}>Vazgeç</Button>
            <Button onClick={() => run.mutate()} disabled={run.isPending}>{run.isPending ? <Spinner /> : <Play />}
              Simülasyonu başlat</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function ScenariosPage() {
  const navigate = useNavigate();
  const scenarios = useQuery({ queryKey: ['scenarios'], queryFn: scenariosApi.list });
  const runs = useQuery({
    queryKey: ['runs'], queryFn: () => scenariosApi.runs(undefined, 30),
    refetchInterval: (q) => (q.state.data?.some((r) => ['PENDING', 'QUEUED', 'RUNNING'].includes(r.status)) ? 3000 : 20_000),
  });
  const [creating, setCreating] = useState(false);
  const [running, setRunning] = useState<Scenario | null>(null);
  const [selected, setSelected] = useState<number[]>([]);
  const toggle = (id: number) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  return (
    <div className="space-y-6">
      <PageHeader eyebrow="Risk analisti" title="Senaryolar"
        description="Makro stres senaryoları ve simülasyon koşuları. Bir senaryoyu çalıştırın, ilerlemesini izleyin ve
          sonuçları karşılaştırın."
        actions={<>
          <Button variant="outline" asChild><Link to="/assistant"><Sparkles /> Asistana sor</Link></Button>
          <Button onClick={() => setCreating(true)}><Plus /> Yeni senaryo</Button>
        </>} />

      {scenarios.isError ? <ErrorState error={scenarios.error} onRetry={() => scenarios.refetch()} />
        : scenarios.isLoading ? <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">{[0, 1, 2].map((i) =>
          <Card key={i} className="p-5"><LoadingBlock rows={4} /></Card>)}</div> : (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {scenarios.data?.map((s) => (
              <Card key={s.id} className="flex animate-fade-up flex-col p-5">
                <div className="mb-2 flex items-start justify-between gap-2">
                  <div>
                    <div className="font-semibold">{s.name}</div>
                    <div className="text-xs text-muted-foreground">{s.code}</div>
                  </div>
                  {s.preset ? <Badge tone="primary">Hazır</Badge> : <Badge tone="neutral">Kullanıcı</Badge>}
                </div>
                <p className="mb-3 line-clamp-3 text-sm text-muted-foreground">{s.description}</p>
                <div className="mb-4 flex flex-wrap gap-1.5">{parameterChips(s.parameters).map((c) =>
                  <Badge key={c} tone="outline" className="font-normal">{c}</Badge>)}</div>
                <Button size="sm" className="mt-auto self-start" onClick={() => setRunning(s)}><Play /> Çalıştır</Button>
              </Card>
            ))}
          </div>
        )}

      <Card>
        <CardHeader className="flex-row items-start justify-between space-y-0">
          <div><CardTitle>Son koşular</CardTitle>
            <CardDescription>Karşılaştırmak için iki veya daha fazla tamamlanmış koşu seçin</CardDescription></div>
          <Button size="sm" variant="outline" disabled={selected.length < 2}
                  onClick={() => navigate(`/runs/compare?ids=${selected.join(',')}`)}>
            <GitCompare /> Karşılaştır ({selected.length})</Button>
        </CardHeader>
        <CardContent className="px-0 pb-2">
          {runs.isLoading ? <LoadingBlock className="px-5" rows={4} /> : runs.isError
            ? <div className="px-5"><ErrorState error={runs.error} compact /></div>
            : !runs.data?.length ? <div className="px-5"><EmptyState title="Henüz koşu yok"
                description="Bir senaryoyu çalıştırın veya asistana bir senaryo sorun." /></div> : (
              <Table>
                <thead><tr className="border-b"><Th className="w-10 pl-5" /><Th>Koşu</Th><Th>Senaryo</Th><Th>Durum</Th>
                  <Th>Başlangıç</Th><Th>İstenme</Th><Th className="pr-5 text-right">Süre</Th></tr></thead>
                <tbody>{runs.data.map((r) => (
                  <Tr key={r.id} className={cn('cursor-pointer', selected.includes(r.id) && 'bg-accent/40')}
                      onClick={() => navigate(`/runs/${r.id}`)}>
                    <Td className="pl-5" onClick={(e) => e.stopPropagation()}>
                      <input type="checkbox" aria-label={`Koşu ${r.id} karşılaştırmaya ekle`} className="size-4 accent-[hsl(var(--primary))]"
                             disabled={r.status !== 'SUCCEEDED'} checked={selected.includes(r.id)} onChange={() => toggle(r.id)} />
                    </Td>
                    <Td className="font-medium">#{r.id}</Td>
                    <Td>{r.scenarioName}<span className="ml-2 text-xs text-muted-foreground">{r.scenarioCode}</span></Td>
                    <Td><div className="flex items-center gap-2"><RunStatusBadge run={r} />
                      {r.status === 'RUNNING' && <Progress value={r.progress} className="w-20" />}</div></Td>
                    <Td className="text-xs">{r.startDate ?? 'Güncel portföy'}</Td>
                    <Td className="text-xs text-muted-foreground">{formatDateTime(r.requestedAt)}</Td>
                    <Td className="pr-5 text-right text-xs">{r.durationSeconds ? `${formatNumber(r.durationSeconds)} sn` : '–'}</Td>
                  </Tr>))}
                </tbody>
              </Table>
            )}
        </CardContent>
      </Card>

      {creating && (
        <Dialog open onOpenChange={(o) => !o && setCreating(false)}>
          <DialogContent title="Yeni senaryo" description="Alanlar ML servisindeki tipli senaryo şemasıyla aynı kurallarla
            doğrulanır.">
            <NewScenarioForm onCreated={(s) => { setCreating(false); setRunning(s); }} />
          </DialogContent>
        </Dialog>
      )}
      {running && scenarios.data && <RunDialog scenario={running} scenarios={scenarios.data} onClose={() => setRunning(null)} />}
    </div>
  );
}
