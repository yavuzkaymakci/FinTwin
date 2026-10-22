import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, stubFetch } from '@/test/render';
import { ChatPanel } from './ChatPanel';

const status = (ai: Record<string, unknown>) => ({
  time: '2026-10-22T10:00:00Z', ml: { reachable: true, circuitBreaker: 'CLOSED', error: null, models: [] },
  ai, replay: { mode: 'IDLE', simulatedTime: null, secondsPerDay: null },
});

describe('ChatPanel', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('explains how to enable a provider when none is configured', async () => {
    stubFetch({ '/api/system/status': status({ provider: 'none', model: null, demo: false, configured: false }),
      '/api/assistants/scenario/conversations': [] });
    renderWithProviders(<ChatPanel kind="scenario" title="Senaryo asistanı" intro="intro" suggestions={['Soru']} />);
    expect(await screen.findByText('Yapay zekâ sağlayıcısı yapılandırılmadı')).toBeInTheDocument();
    expect(screen.getByLabelText('Mesaj')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Soru' })).not.toBeInTheDocument();
  });

  it('labels demo mode and offers example questions', async () => {
    stubFetch({ '/api/system/status': status({ provider: 'ai-demo', model: 'demo', demo: true, configured: true }),
      '/api/assistants/scenario/conversations': [] });
    renderWithProviders(<ChatPanel kind="scenario" title="Senaryo asistanı" intro="intro" suggestions={['Soru']} />);
    expect(await screen.findByText(/Demo modu · yanıtlar önceden hazırlanmış/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Soru' })).toBeInTheDocument();
  });
});
