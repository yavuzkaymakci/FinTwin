import { describe, expect, it } from 'vitest';
import { EMPTY_FORM, validateScenario } from './scenarioForm';

describe('validateScenario', () => {
  it('builds the typed request from a valid form, accepting Turkish decimals', () => {
    const { errors, request } = validateScenario({ ...EMPTY_FORM, code: 'kur_soku', name: 'Kur şoku',
      monthlyInflationPct: '3,5 3 2,5' });
    expect(errors).toEqual({});
    expect(request?.code).toBe('KUR_SOKU');
    expect(request?.parameters.monthlyInflationPct).toEqual([3.5, 3, 2.5]);
    expect(request?.parameters.usdTryChangePct).toBe(15);
  });

  it('reports the backend ranges per field', () => {
    const { errors, request } = validateScenario({ ...EMPTY_FORM, code: '1X', name: '', usdTryChangePct: '250',
      policyRateChangeBp: '12.5', horizonMonths: '2', monthlyInflationPct: '1 2 3' });
    expect(request).toBeUndefined();
    expect(errors.code).toBeDefined();
    expect(errors.name).toBe('Ad gerekli');
    expect(errors.usdTryChangePct).toBe('-50 ile 200 arasında olmalı');
    expect(errors.policyRateChangeBp).toBe('Tam sayı olmalı');
    expect(errors.monthlyInflationPct).toBe('Ufuktan (2 ay) fazla değer var');
  });
});
