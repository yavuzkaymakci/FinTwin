import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { DeltaBadge, RiskGradeBadge } from './common';

describe('RiskGradeBadge', () => {
  it('shows the grade coloured by the grade scale', () => {
    renderWithProviders(<RiskGradeBadge grade={9} />);
    const badge = screen.getByLabelText('Risk derecesi 9');
    expect(badge).toHaveTextContent('9');
    expect(badge.getAttribute('style')).toContain('--grade-9');
  });

  it('renders a dash without a grade', () => {
    renderWithProviders(<RiskGradeBadge grade={null} />);
    expect(screen.getByText('–')).toBeInTheDocument();
  });
});

describe('DeltaBadge', () => {
  it('marks a risk increase as negative and a decrease as positive', () => {
    const fmt = (v: number) => v.toFixed(2);
    const { rerender } = renderWithProviders(<DeltaBadge value={0.02} format={fmt} />);
    expect(screen.getByText('0.02').className).toContain('negative');
    rerender(<DeltaBadge value={-0.02} format={fmt} />);
    expect(screen.getByText('-0.02').className).toContain('positive');
  });
});
