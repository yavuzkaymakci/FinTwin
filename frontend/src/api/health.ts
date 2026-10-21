export type HealthStatus = 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN';

export interface HealthComponent {
  status: HealthStatus;
}

export interface BackendHealth {
  status: HealthStatus;
  components?: Record<string, HealthComponent>;
}

export async function fetchBackendHealth(signal?: AbortSignal): Promise<BackendHealth> {
  const response = await fetch('/actuator/health', { signal });
  // Actuator returns 503 with a JSON body when DOWN, so parse the body whenever it is JSON.
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('json')) {
    throw new Error(`Unexpected response (HTTP ${response.status})`);
  }
  return (await response.json()) as BackendHealth;
}
