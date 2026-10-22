import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, stubFetch } from '@/test/render';
import { StatusBanner } from './StatusBanner';

const status = (reachable: boolean, circuitBreaker: string) => ({
  time: '2026-10-22T10:00:00Z', ml: { reachable, circuitBreaker, error: null, models: [] },
  ai: { provider: 'none', model: null, demo: false, configured: false },
  replay: { mode: 'IDLE', simulatedTime: null, secondsPerDay: null },
});

describe('StatusBanner', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('warns when the circuit breaker is open', async () => {
    stubFetch({ '/api/system/status': status(false, 'OPEN') });
    renderWithProviders(<StatusBanner />);
    expect(await screen.findByText(/devre kesici açık/)).toBeInTheDocument();
    expect(screen.getByText(/Kayıtlı skorlar/)).toBeInTheDocument();
  });

  it('stays hidden while the ML service is healthy', async () => {
    const fetchMock = stubFetch({ '/api/system/status': status(true, 'CLOSED') });
    renderWithProviders(<StatusBanner />);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
