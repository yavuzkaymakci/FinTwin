import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BackendHealthCard } from './BackendHealthCard';

function mockFetch(response: Response | Error) {
  const fn = response instanceof Error ? vi.fn().mockRejectedValue(response) : vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fn);
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('BackendHealthCard', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows UP and component statuses when backend is healthy', async () => {
    mockFetch(jsonResponse({ status: 'UP', components: { db: { status: 'UP' } } }));

    render(<BackendHealthCard />);

    expect(await screen.findByText('db')).toBeInTheDocument();
    expect(screen.getByTestId('backend-status')).toHaveTextContent('UP');
  });

  it('shows DOWN when actuator reports 503', async () => {
    mockFetch(jsonResponse({ status: 'DOWN' }, 503));

    render(<BackendHealthCard />);

    expect(await screen.findByText('DOWN')).toBeInTheDocument();
  });

  it('shows UNREACHABLE when the request fails', async () => {
    mockFetch(new TypeError('Failed to fetch'));

    render(<BackendHealthCard />);

    expect(await screen.findByText('UNREACHABLE')).toBeInTheDocument();
    expect(screen.getByText(/Could not reach backend/)).toBeInTheDocument();
  });
});
