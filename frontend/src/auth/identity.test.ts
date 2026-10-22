import { afterEach, describe, expect, it } from 'vitest';
import { asAnalyst, asCustomer, getIdentity, identityHeaders, setIdentity } from './identity';

describe('identity', () => {
  afterEach(() => setIdentity(asAnalyst()));

  it('sends analyst headers without a customer id', () => {
    expect(identityHeaders(asAnalyst())).toEqual({ 'X-Demo-User': 'ayse.analist', 'X-Demo-Role': 'RISK_ANALYST' });
  });

  it('sends the selected customer for the customer role and persists the choice', () => {
    setIdentity(asCustomer(42));
    expect(getIdentity().customerId).toBe(42);
    expect(identityHeaders()).toEqual({ 'X-Demo-User': 'musteri-42', 'X-Demo-Role': 'CUSTOMER',
      'X-Demo-Customer-Id': '42' });
    expect(JSON.parse(localStorage.getItem('fintwin.identity') ?? '{}').customerId).toBe(42);
  });
});
