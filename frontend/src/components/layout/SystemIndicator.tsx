import { Link } from 'react-router-dom';
import { useIdentity } from '@/auth/identity';
import { Dot } from '@/components/common';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/radix';
import { formatDateTime } from '@/lib/format';
import { statusLabel } from '@/lib/labels';
import { mlDegraded, useSystemStatus } from '@/lib/queries';

/** Compact system health in the header; details in a popover. */
export function SystemIndicator() {
  const { data, isError, isLoading } = useSystemStatus();
  const identity = useIdentity();
  const tone = isError ? 'negative' : isLoading ? 'neutral' : mlDegraded(data) ? 'warning' : 'positive';
  const label = isError ? 'Backend kapalı' : isLoading ? 'Kontrol ediliyor' : mlDegraded(data) ? 'Kısmi hizmet'
    : 'Sistem çalışıyor';
  const ai = data?.ai;
  const aiLabel = !ai ? '–' : !ai.configured ? 'Yapılandırılmadı' : ai.demo ? 'Demo modu'
    : `${ai.provider}${ai.model ? ` · ${ai.model}` : ''}`;

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button className="flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium transition-colors
          hover:bg-muted" aria-label={`Sistem durumu: ${label}`}>
          <span className="relative flex">
            <Dot tone={tone} />
            {tone === 'positive' && <span className="absolute inset-0 animate-ping rounded-full bg-positive/40" />}
          </span>
          <span className="hidden sm:inline">{label}</span>
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 text-sm">
        <div className="mb-2 font-medium">Sistem durumu</div>
        <dl className="space-y-1.5 text-xs">
          <Row label="Backend" value={isError ? 'Ulaşılamıyor' : 'Çalışıyor'} ok={!isError} />
          <Row label="ML servisi" value={data ? (data.ml.reachable ? 'Çalışıyor' : 'Ulaşılamıyor') : '–'}
               ok={!!data?.ml.reachable} />
          <Row label="Devre kesici" value={data?.ml.circuitBreaker ?? '–'} ok={data?.ml.circuitBreaker === 'CLOSED'} />
          <Row label="Yapay zekâ" value={aiLabel} ok={!!ai?.configured} />
          <Row label="Veri akışı" value={statusLabel(data?.replay.mode)} ok />
          {data?.replay.simulatedTime && <Row label="Simüle zaman" value={formatDateTime(data.replay.simulatedTime)} ok />}
        </dl>
        {identity.role === 'RISK_ANALYST' && (
          <Link to="/system" className="mt-3 inline-block text-xs font-medium text-primary hover:underline">
            Ayrıntılar →
          </Link>
        )}
      </PopoverContent>
    </Popover>
  );
}

function Row({ label, value, ok }: { label: string; value: string; ok: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="flex items-center gap-1.5 text-right font-medium"><Dot tone={ok ? 'positive' : 'warning'} />{value}</dd>
    </div>
  );
}
