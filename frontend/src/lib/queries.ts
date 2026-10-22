import { useQuery } from '@tanstack/react-query';
import { systemApi } from '@/api/endpoints';

/** System status for the header indicator and the ML warning banner (polled every 15 s). */
export function useSystemStatus() {
  return useQuery({ queryKey: ['system-status'], queryFn: systemApi.status, refetchInterval: 15_000, retry: 0 });
}

/** True when the ML service is unreachable or its circuit breaker is not closed. */
export function mlDegraded(status: { ml: { reachable: boolean; circuitBreaker: string } } | undefined): boolean {
  return !!status && (!status.ml.reachable || status.ml.circuitBreaker !== 'CLOSED');
}
