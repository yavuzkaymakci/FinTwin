import { useCallback, useEffect, useState } from 'react';
import { fetchBackendHealth, type BackendHealth } from '../api/health';

const POLL_INTERVAL_MS = 10_000;

type State =
  | { kind: 'loading' }
  | { kind: 'loaded'; health: BackendHealth; checkedAt: Date }
  | { kind: 'error'; message: string; checkedAt: Date };

export function BackendHealthCard() {
  const [state, setState] = useState<State>({ kind: 'loading' });

  const check = useCallback(async (signal?: AbortSignal) => {
    try {
      const health = await fetchBackendHealth(signal);
      setState({ kind: 'loaded', health, checkedAt: new Date() });
    } catch (error) {
      if (signal?.aborted) return;
      const message = error instanceof Error ? error.message : 'Unknown error';
      setState({ kind: 'error', message, checkedAt: new Date() });
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void check(controller.signal);
    const timer = window.setInterval(() => void check(controller.signal), POLL_INTERVAL_MS);
    return () => {
      controller.abort();
      window.clearInterval(timer);
    };
  }, [check]);

  const status = state.kind === 'loaded' ? state.health.status : state.kind === 'error' ? 'UNREACHABLE' : '…';

  return (
    <section className="card" aria-labelledby="backend-health-title">
      <header className="card-header">
        <h2 id="backend-health-title">Backend</h2>
        <span className={`badge badge-${status.toLowerCase()}`} data-testid="backend-status">
          {status}
        </span>
      </header>

      {state.kind === 'loaded' && state.health.components && (
        <ul className="components">
          {Object.entries(state.health.components).map(([name, component]) => (
            <li key={name}>
              <span>{name}</span>
              <span className={`badge badge-${component.status.toLowerCase()}`}>{component.status}</span>
            </li>
          ))}
        </ul>
      )}

      {state.kind === 'error' && <p className="error">Could not reach backend: {state.message}</p>}

      <footer className="card-footer">
        {state.kind !== 'loading' && <span>Last checked {state.checkedAt.toLocaleTimeString()}</span>}
        <button type="button" onClick={() => void check()}>
          Refresh
        </button>
      </footer>
    </section>
  );
}
