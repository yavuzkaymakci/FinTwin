import type { ScenarioParameters, ShockProfile } from '@/api/types';

/** Form state of a new scenario (text inputs; numbers are parsed on validation). */
export interface ScenarioForm {
  code: string;
  name: string;
  description: string;
  horizonMonths: string;
  usdTryChangePct: string;
  policyRateChangeBp: string;
  monthlyInflationPct: string;
  wageIndexationPct: string;
  wageAdjustmentLagMonths: string;
  shockProfile: ShockProfile;
  shockRampMonths: string;
  monteCarloPaths: string;
  seed: string;
}

export const EMPTY_FORM: ScenarioForm = {
  code: '', name: '', description: '', horizonMonths: '12', usdTryChangePct: '15', policyRateChangeBp: '0',
  monthlyInflationPct: '2,0', wageIndexationPct: '90', wageAdjustmentLagMonths: '6', shockProfile: 'IMMEDIATE',
  shockRampMonths: '6', monteCarloPaths: '200', seed: '42',
};

export type FormErrors = Partial<Record<keyof ScenarioForm, string>>;

/** Turkish or plain decimal ("2,5" or "2.5"). */
function parseNumber(text: string): number | null {
  const t = text.trim().replace(',', '.');
  if (!/^-?\d+(\.\d+)?$/.test(t)) return null;
  return Number(t);
}

function ranged(errors: FormErrors, key: keyof ScenarioForm, text: string, min: number, max: number,
                integer = false): number {
  const n = parseNumber(text);
  if (n === null) errors[key] = 'Bir sayı girin';
  else if (integer && !Number.isInteger(n)) errors[key] = 'Tam sayı olmalı';
  else if (n < min || n > max) errors[key] = `${min} ile ${max} arasında olmalı`;
  return n ?? 0;
}

/**
 * Validates the form with the same rules as the backend (Bean Validation on ScenarioParameters) and returns the
 * typed request, or the errors per field.
 */
export function validateScenario(form: ScenarioForm): { errors: FormErrors; request?: { code: string; name: string;
  description: string; parameters: ScenarioParameters } } {
  const errors: FormErrors = {};
  const code = form.code.trim().toUpperCase();
  if (!/^[A-Z][A-Z0-9_]{1,63}$/.test(code)) errors.code = 'Büyük harfle başlayan, harf/rakam/_ içeren 2–64 karakter';
  if (!form.name.trim()) errors.name = 'Ad gerekli';
  else if (form.name.trim().length > 128) errors.name = 'En fazla 128 karakter';
  const horizon = ranged(errors, 'horizonMonths', form.horizonMonths, 1, 36, true);
  const usd = ranged(errors, 'usdTryChangePct', form.usdTryChangePct, -50, 200);
  const bp = ranged(errors, 'policyRateChangeBp', form.policyRateChangeBp, -3000, 3000, true);
  const parts = form.monthlyInflationPct.split(/[;\s]+|,(?=\s)|\s*\|\s*/).map((p) => p.trim()).filter(Boolean);
  const inflation = parts.map(parseNumber);
  if (!parts.length) errors.monthlyInflationPct = 'En az bir aylık değer girin';
  else if (inflation.some((v) => v === null)) errors.monthlyInflationPct = 'Değerleri boşluk veya noktalı virgülle ayırın';
  else if (inflation.some((v) => (v as number) < -5 || (v as number) > 30)) {
    errors.monthlyInflationPct = 'Her değer -5 ile 30 arasında olmalı';
  } else if (!errors.horizonMonths && parts.length > horizon) {
    errors.monthlyInflationPct = `Ufuktan (${horizon} ay) fazla değer var`;
  }
  const wage = ranged(errors, 'wageIndexationPct', form.wageIndexationPct, 0, 150);
  const lag = ranged(errors, 'wageAdjustmentLagMonths', form.wageAdjustmentLagMonths, 0, 12, true);
  const ramp = ranged(errors, 'shockRampMonths', form.shockRampMonths, 1, 36, true);
  const paths = ranged(errors, 'monteCarloPaths', form.monteCarloPaths, 10, 2000, true);
  const seed = ranged(errors, 'seed', form.seed, 0, 2_147_483_647, true);
  if (Object.keys(errors).length) return { errors };
  return {
    errors,
    request: {
      code, name: form.name.trim(), description: form.description.trim(),
      parameters: {
        horizonMonths: horizon, usdTryChangePct: usd, policyRateChangeBp: bp,
        monthlyInflationPct: inflation as number[], wageIndexationPct: wage, wageAdjustmentLagMonths: lag,
        segmentWageRules: [], shockProfile: form.shockProfile, shockRampMonths: ramp, monteCarloPaths: paths, seed,
      },
    },
  };
}
