import * as React from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowDownLeft, ArrowUpRight, CalendarClock, Database, FastForward, Pause, Play, Radio, RotateCcw, Zap } from 'lucide-react';
import { liveApi } from '@/api/endpoints';
import type { ReplayStatus, TwinLiveView } from '@/api/types';
import { EmptyState, ErrorState, KpiCard, LoadingBlock, PageHeader, Spinner } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardContent, CardHeader, CardTitle, Progress } from '@/components/ui/primitives';
import { Dialog, DialogClose, DialogContent, DialogTrigger, Slider } from '@/components/ui/radix';
import { formatDate, formatDateTime, formatMoney, formatNumber, formatTime } from '@/lib/format';
import { eventLabel } from '@/lib/labels';
import { cn } from '@/lib/utils';

/** Real seconds per simulated day; lower is faster. */
const SPEEDS = [60, 20, 10, 5, 2, 1, 0.5];

function speedText(secondsPerDay: number): string {
  return secondsPerDay >= 1 ? `1 gün = ${formatNumber(secondsPerDay, secondsPerDay % 1 ? 1 : 0)} sn`
    : `1 gün = ${formatNumber(secondsPerDay * 1000)} ms`;
}

function ModeBadge({ mode }: { mode: string }) {
  if (mode === 'LIVE') return <Badge tone="positive"><span className="size-1.5 animate-pulse-dot rounded-full bg-positive" /> Canlı</Badge>;
  if (mode === 'BULK') return <Badge tone="info"><Spinner className="size-3" /> Toplu yükleme</Badge>;
  if (mode === 'PREPARING') return <Badge tone="info"><Spinner className="size-3" /> Hazırlanıyor</Badge>;
  return <Badge tone="neutral">Beklemede</Badge>;
}

function DemoPrepareDialog({ disabled }: { disabled: boolean }) {
  const client = useQueryClient();
  const [days, setDays] = React.useState(14);
  const prepare = useMutation({ mutationFn: () => liveApi.prepareDemo(days),
    onSuccess: () => client.invalidateQueries({ queryKey: ['replay'] }) });
  return (
    <Dialog>
      <DialogTrigger asChild><Button variant="outline" disabled={disabled}><RotateCcw /> Demo için hazırla</Button></DialogTrigger>
      <DialogContent title="Canlı demo için hazırla"
        description="İkiz sıfırlanır ve veri akışı, verinin son N günü hariç baştan yeniden işlenir. Sonra canlı akış kalan günleri gerçek zamanlı oynatır.">
        <div className="space-y-4">
          <div>
            <div className="mb-2 flex justify-between text-sm"><span>Canlı oynatılacak son gün sayısı</span>
              <b>{days} gün</b></div>
            <Slider min={1} max={31} step={1} value={[days]} onValueChange={(v) => setDays(v[0])} aria-label="Gün sayısı" />
          </div>
          <div className="rounded-md border border-warning/40 bg-warning-soft/40 p-3 text-xs">
            Toplu yeniden işleme birkaç dakika sürer (yaklaşık 7 dk). Bu sırada ikiz ekranları kısmi veri gösterir.
            Risk skorları ve simülasyon sonuçları silinmez.
          </div>
          {prepare.isError && <ErrorState compact error={prepare.error} title="Hazırlık başlatılamadı" />}
          {prepare.isSuccess && <div className="text-sm text-positive">Başladı: {formatDate(prepare.data.replayedUntil)} tarihine
            kadar toplu yükleniyor; canlı akış {formatDate(prepare.data.liveFrom)} tarihinden başlayabilir.</div>}
          <div className="flex justify-end gap-2">
            <DialogClose asChild><Button variant="ghost">Kapat</Button></DialogClose>
            <Button onClick={() => prepare.mutate()} disabled={prepare.isPending || prepare.isSuccess}>
              {prepare.isPending ? <Spinner /> : <RotateCcw />} Hazırla</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function Controls({ status }: { status: ReplayStatus }) {
  const client = useQueryClient();
  const [speed, setSpeed] = React.useState(() => Math.max(0, SPEEDS.indexOf(status.secondsPerDay ?? 5)));
  const done = () => client.invalidateQueries({ queryKey: ['replay'] });
  const start = useMutation({ mutationFn: () => liveApi.startLive(SPEEDS[speed]), onSuccess: done });
  const stop = useMutation({ mutationFn: liveApi.stop, onSuccess: done });
  const change = useMutation({ mutationFn: (s: number) => liveApi.speed(s), onSuccess: done });
  const live = status.mode === 'LIVE';
  const busy = status.mode === 'BULK' || status.mode === 'PREPARING';
  const atEnd = status.totalEvents > 0 && status.position >= status.totalEvents;
  const error = start.error ?? stop.error ?? change.error;
  return (
    <Card>
      <CardHeader className="pb-3"><CardTitle className="text-base">Akış kontrolü</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div>
          <div className="mb-2 flex items-center justify-between text-sm">
            <span className="text-muted-foreground">Hız</span><b>{speedText(SPEEDS[speed])}</b>
          </div>
          <Slider min={0} max={SPEEDS.length - 1} step={1} value={[speed]} aria-label="Akış hızı"
            onValueChange={(v) => setSpeed(v[0])} onValueCommit={(v) => { if (live) change.mutate(SPEEDS[v[0]]); }} />
          <div className="mt-1 flex justify-between text-[10px] text-muted-foreground"><span>Yavaş</span><span>Hızlı</span></div>
        </div>
        <div className="flex flex-wrap gap-2">
          {live ? <Button variant="secondary" onClick={() => stop.mutate()} disabled={stop.isPending}><Pause /> Durdur</Button>
            : <Button onClick={() => start.mutate()} disabled={busy || atEnd || start.isPending}>
              {start.isPending ? <Spinner /> : <Play />} Canlı akışı başlat</Button>}
          {live && <Button variant="outline" onClick={() => {
            const next = Math.min(SPEEDS.length - 1, speed + 1);
            setSpeed(next);
            change.mutate(SPEEDS[next]);
          }}><FastForward /> Hızlandır</Button>}
          <DemoPrepareDialog disabled={live || busy} />
        </div>
        {atEnd && !live && <p className="text-xs text-muted-foreground">Tüm veri işlendi; akacak işlem kalmadı. Canlı demo
          için "Demo için hazırla" ile son günleri yeniden oynatılabilir hale getirin.</p>}
        {busy && <p className="text-xs text-muted-foreground">Toplu yükleme sürüyor; bitince canlı akışı başlatabilirsiniz.</p>}
        {error && <ErrorState compact error={error} title="İşlem yapılamadı" />}
      </CardContent>
    </Card>
  );
}

function EventRow({ e, fresh }: { e: TwinLiveView['recent'][number]; fresh: boolean }) {
  const credit = e.direction === 'CREDIT';
  return (
    <li className={cn('flex items-center gap-3 rounded-md px-2 py-2 transition-colors', fresh && 'animate-fade-up bg-accent/60')}>
      <div className={cn('flex size-8 shrink-0 items-center justify-center rounded-full',
        e.direction ? (credit ? 'bg-positive-soft text-positive' : 'bg-negative-soft text-negative') : 'bg-muted text-muted-foreground')}>
        {e.direction ? (credit ? <ArrowDownLeft className="size-4" /> : <ArrowUpRight className="size-4" />) : <Zap className="size-4" />}
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium">{e.text ?? eventLabel(e.eventType)}</div>
        <div className="text-[11px] text-muted-foreground">
          <Link to={`/customers/${e.customerId}`} className="text-primary hover:underline">Müşteri {e.customerId}</Link>
          {' '}· {eventLabel(e.eventType)} · #{e.customerSequence} · {formatDateTime(e.occurredAt)}
        </div>
      </div>
      {e.amount !== null && (
        <div className={cn('shrink-0 text-sm font-semibold tabular-nums', credit ? 'text-positive' : 'text-foreground')}>
          {credit ? '+' : '−'}{formatMoney(Number(e.amount))}
        </div>
      )}
    </li>
  );
}

export function LivePage() {
  const replay = useQuery({ queryKey: ['replay'], queryFn: liveApi.replay, refetchInterval: 2000 });
  const live = replay.data?.mode === 'LIVE' || replay.data?.mode === 'BULK';
  const twin = useQuery({ queryKey: ['live-twin'], queryFn: () => liveApi.twin(15), refetchInterval: live ? 1500 : 10000 });
  const seen = React.useRef<Set<string>>(new Set());
  const [freshKeys, setFreshKeys] = React.useState<Set<string>>(new Set());

  React.useEffect(() => {
    const recent = twin.data?.recent ?? [];
    const keys = recent.map((e) => `${e.customerId}:${e.customerSequence}`);
    const first = seen.current.size === 0;
    setFreshKeys(new Set(first ? [] : keys.filter((k) => !seen.current.has(k))));
    keys.forEach((k) => seen.current.add(k));
  }, [twin.data]);

  if (replay.isLoading) return <><PageHeader title="Canlı akış" /><LoadingBlock rows={6} /></>;
  if (replay.error || !replay.data) {
    return <><PageHeader title="Canlı akış" /><ErrorState error={replay.error} onRetry={() => void replay.refetch()} /></>;
  }
  const r = replay.data;
  const t = twin.data;
  const progress = r.totalEvents ? r.position / r.totalEvents : 0;
  return (
    <>
      <PageHeader title="Canlı akış" eyebrow="Event sourcing"
        description="Çekirdek bankacılık işlemleri Kafka'ya akıyor; her olay müşterinin dijital ikizini anında güncelliyor."
        actions={<ModeBadge mode={r.mode} />} />
      <div className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard label="Simüle tarih" icon={<CalendarClock />} value={r.simulatedTime ? formatDate(r.simulatedTime) : '–'}
          hint={r.simulatedTime ? `Saat ${formatTime(r.simulatedTime)}` : 'Akış başlamadı'} />
        <KpiCard label="Yayımlanan olay (oturum)" icon={<Radio />} value={formatNumber(r.sessionPublished)}
          hint={`${formatNumber(r.eventsPerSecond, 1)} olay/sn · toplam ${formatNumber(r.publishedTotal)}`} />
        <KpiCard label="İkize işlenen olay" icon={<Zap />} tone="positive" value={t ? formatNumber(t.processed) : '–'}
          hint={t ? `${formatNumber(t.duplicates)} tekrar · ${formatNumber(t.deadLettered)} DLT` : undefined} loading={twin.isLoading} />
        <KpiCard label="Olay deposu" icon={<Database />} value={t ? formatNumber(t.storedEvents) : '–'}
          hint={t ? `sayım ${formatTime(t.countedAt)}` : undefined} loading={twin.isLoading} />
      </div>
      <div className="grid gap-4 lg:grid-cols-[340px_1fr]">
        <div className="space-y-4">
          <Controls status={r} />
          <Card>
            <CardHeader className="pb-3"><CardTitle className="text-base">Akış konumu</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-xs">
              <Progress value={progress * 100} />
              <div className="flex justify-between text-muted-foreground">
                <span>{formatNumber(r.position)} / {formatNumber(r.totalEvents)} olay</span>
                <span>{formatNumber(progress * 100, 1)}%</span>
              </div>
              <div className="flex justify-between text-muted-foreground"><span>Veri aralığı</span>
                <span>{formatDate(r.feedStart)} – {formatDate(r.feedEnd)}</span></div>
              {r.lastError && <div className="text-negative">{r.lastError}</div>}
            </CardContent>
          </Card>
        </div>
        <Card className="min-h-[420px]">
          <CardHeader className="flex-row items-center justify-between pb-2">
            <CardTitle className="text-base">Son işlenen olaylar</CardTitle>
            {live && <span className="flex items-center gap-1.5 text-xs text-positive">
              <span className="size-2 animate-pulse-dot rounded-full bg-positive" /> işlemler akıyor, ikiz güncelleniyor</span>}
          </CardHeader>
          <CardContent>
            {twin.isLoading ? <LoadingBlock rows={8} /> : twin.error ? <ErrorState compact error={twin.error} />
              : !t?.recent.length ? <EmptyState title="Henüz olay yok" description="Canlı akışı başlatınca olaylar burada belirir." />
                : <ul className="divide-y">{t.recent.map((e) => {
                  const key = `${e.customerId}:${e.customerSequence}`;
                  return <EventRow key={key} e={e} fresh={freshKeys.has(key)} />;
                })}</ul>}
          </CardContent>
        </Card>
      </div>
    </>
  );
}
