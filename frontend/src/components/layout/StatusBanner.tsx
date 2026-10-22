import { AlertTriangle, ServerCrash } from 'lucide-react';
import { mlDegraded, useSystemStatus } from '@/lib/queries';

/**
 * Warning band under the header: backend unreachable, or ML service down / circuit breaker open. Stored data keeps
 * being shown; only new scores, simulations and assistant answers are affected.
 */
export function StatusBanner() {
  const { data, isError } = useSystemStatus();
  if (isError) {
    return (
      <div role="status" className="flex items-center gap-2 border-b border-negative/30 bg-negative-soft px-6 py-2
        text-sm text-negative">
        <ServerCrash className="size-4 shrink-0" />
        Backend'e ulaşılamıyor. Servislerin çalıştığından emin olun (scripts\start-all.bat).
      </div>
    );
  }
  if (!mlDegraded(data)) return null;
  const open = data?.ml.circuitBreaker === 'OPEN';
  return (
    <div role="status" className="flex items-center gap-2 border-b border-warning/30 bg-warning-soft px-6 py-2 text-sm
      text-warning">
      <AlertTriangle className="size-4 shrink-0" />
      <span>
        <strong className="font-semibold">ML servisi {open ? 'devre dışı (devre kesici açık)' : 'yanıt vermiyor'}.</strong>{' '}
        Kayıtlı skorlar ve sonuçlar gösterilmeye devam ediyor; yeni skorlama ve simülasyonlar şu an çalışmaz.
      </span>
    </div>
  );
}
