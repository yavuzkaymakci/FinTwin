/** Risk grade scale (1 best .. 10 worst) as in the ML service's master scale (app/grades.py). */

export const GRADE_BOUNDS = [0.001, 0.0025, 0.005, 0.01, 0.02, 0.04, 0.08, 0.15, 0.3];

export function gradeOf(pd: number): number {
  let grade = 1;
  for (const bound of GRADE_BOUNDS) {
    if (pd >= bound) grade += 1;
  }
  return grade;
}

/** CSS colour of a grade (design token --grade-N). */
export function gradeColor(grade: number): string {
  const g = Math.min(10, Math.max(1, Math.round(grade)));
  return `hsl(var(--grade-${g}))`;
}

export function gradeBand(grade: number): { label: string; tone: 'positive' | 'warning' | 'negative' } {
  if (grade <= 3) return { label: 'Düşük risk', tone: 'positive' };
  if (grade <= 6) return { label: 'Orta risk', tone: 'warning' };
  return { label: 'Yüksek risk', tone: 'negative' };
}

/** PD range covered by a grade, as text ("%0,25 – %0,50"). */
export function gradeRange(grade: number): string {
  const low = grade === 1 ? 0 : GRADE_BOUNDS[grade - 2];
  const high = grade === 10 ? null : GRADE_BOUNDS[grade - 1];
  const pct = (v: number) => `%${(v * 100).toLocaleString('tr-TR', { maximumFractionDigits: 2 })}`;
  return high === null ? `≥ ${pct(low)}` : `${pct(low)} – ${pct(high)}`;
}
