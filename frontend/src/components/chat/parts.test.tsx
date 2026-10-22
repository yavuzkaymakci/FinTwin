import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { ToolCall } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { ConfirmationCard, GoalCard, pendingConfirmation, RunLinks, ToolTag, ValidationWarning } from './parts';

const call = (name: string, result: unknown, args = '{}'): ToolCall => ({ name, arguments: args,
  result: JSON.stringify(result), ok: true, durationMs: 12 });

const confirmation = call('startSimulation', { status: 'CONFIRMATION_REQUIRED', scenario: { scenarioId: 7,
  name: 'Kur şoku', code: 'FX_30', parameters: { usdTryChangePct: 30, policyRateChangeBp: 500,
    monthlyInflationPct: [4, 3], horizonMonths: 12, monteCarloPaths: 200 } } });

describe('pendingConfirmation', () => {
  it('finds a simulation waiting for confirmation', () => {
    expect(pendingConfirmation([call('createScenario', { id: 7 }), confirmation])?.scenarioId).toBe(7);
  });

  it('is empty once the simulation started', () => {
    expect(pendingConfirmation([confirmation, call('startSimulation', { status: 'STARTED', runId: 3 })])).toBeNull();
    expect(pendingConfirmation([])).toBeNull();
  });
});

describe('ConfirmationCard', () => {
  it('shows the parameters and wires both buttons', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    renderWithProviders(<ConfirmationCard pending={pendingConfirmation([confirmation])!} onConfirm={onConfirm}
      onCancel={onCancel} />);
    expect(screen.getByText('%30,0')).toBeInTheDocument();
    expect(screen.getByText('500 bp')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Onayla/ }));
    await userEvent.click(screen.getByRole('button', { name: /Vazgeç/ }));
    expect(onConfirm).toHaveBeenCalledOnce();
    expect(onCancel).toHaveBeenCalledOnce();
  });
});

describe('ToolTag', () => {
  it('reveals the call parameters on click', async () => {
    renderWithProviders(<ToolTag call={call('createScenario', { id: 7 }, '{"usdTryChangePct":30}')} />);
    await userEvent.click(screen.getByRole('button', { name: /createScenario çağrıldı/ }));
    expect(await screen.findByText(/"usdTryChangePct": 30/)).toBeInTheDocument();
  });
});

describe('run links and validation', () => {
  it('links started runs to their result page', () => {
    renderWithProviders(<RunLinks runIds={[12]} />);
    expect(screen.getByRole('link', { name: /Koşu #12/ })).toHaveAttribute('href', '/runs/12');
  });

  it('flags numbers the validator could not verify', () => {
    renderWithProviders(<ValidationWarning validation={{ passed: false, unverified: ['%7,1'] }} />);
    expect(screen.getByText(/1 sayı doğrulanamadı/)).toBeInTheDocument();
  });
});

describe('GoalCard', () => {
  it('shows the goal probabilities and the required monthly saving', () => {
    renderWithProviders(<GoalCard call={call('simulateSavingsGoal', { targetAmount: 100000, months: 12,
      probabilityWithoutChanges: 0.35, probabilityWithChanges: 0.62, extraMonthlySaving: 0,
      requiredExtraMonthlySaving: 2500, categoryCuts: [{ category: 'RESTAURANT', cutPct: 50 }],
      monthlySavingFromCuts: 1200, balanceAtHorizonWithChanges: { p10: 80000, p50: 104000, p90: 130000 },
      assumptions: { targetInTodaysPrices: 78000 } })} />);
    expect(screen.getByText('%35')).toBeInTheDocument();
    expect(screen.getByText('%62')).toBeInTheDocument();
    expect(screen.getByText('2.500 ₺')).toBeInTheDocument();
  });
});
