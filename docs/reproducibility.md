# Reproducibility — reference values

`scripts/setup-fresh.bat` rebuilds the whole system from an empty database. The data set is deterministic
(seed 42, 5,000 customers × 24 months, end month 2026-09), so a fresh installation must reproduce the numbers
below. The machine-readable copy is [reproducibility.json](reproducibility.json); the last setup step
(`setup_steps.py check`, log `logs/setup-check.log`) compares the new installation against it and prints a
warning for every difference. It never fails the setup.

Reference machine: development laptop, Windows 10, 16 GB RAM, 2026-10-22 (commit of this file).

## What must match exactly

| Area | Value | Source |
|------|-------|--------|
| `customer` | 5,000 | generation report (docs/data-generation.md) |
| `account` | 11,247 | 〃 |
| `card_statement` | 71,427 | 〃 |
| `loan` / `loan_installment` | 2,178 / 57,048 | 〃 |
| `bank_transaction` | 5,568,659 | 〃 |
| `customer_event` | 4,687 (195 DEFAULT, 422 CHURN, 4,070 LATE_PAYMENT) | 〃 |
| `merchant` | 1,164 | 〃 |
| `ground_truth.customer_monthly_state` | 104,077 | 〃 |
| Twins / month-start snapshots | 5,000 / 99,693 | docs/twin.md, `GET /api/admin/twins/stats` |
| Events applied to the twins | 5,688,485 | live view (`Σ last_seq` = event-store rows) |
| Sample customer 6 | 980 events, checking balance ≈ 92,682 TRY | twin API |

Any difference here means the generator or the replay is not deterministic on the new machine (JDK, time zone,
locale) and must be investigated before anything else.

## What may drift slightly

| Area | Reference | Tolerance in the check |
|------|-----------|------------------------|
| PD model (test, out-of-time) | AUC 0.9723, Gini 0.9446, KS 0.8452, Brier 0.01275 | ±1 % relative |
| Churn model (test) | AUC 0.7610, Gini 0.5219, KS 0.3641, Brier 0.02547 | ±1 % |
| NLP categorisation | macro F1 0.9554 (char+word variant) | ±1 % |
| Portfolio scoring | 4,383 scored customers, mean PD 1.35 %, 171 high-risk (grade ≥ 8) | ±2 % / ±5 % |
| Customer 6 risk | PD 1.40 %, grade 5 | ±2 % |
| Preset scenarios (12 m, 200 paths, start = current state) | see below | ±5 % |

| Preset | Default rate 12 m | Expected loss |
|--------|-------------------|---------------|
| BASE | 3.23 % | 5.71 M TRY |
| FX_SHOCK | 4.21 % | 8.01 M TRY |
| RATE_SHOCK | 3.38 % | 6.02 M TRY |
| STAGFLATION | 4.72 % | 9.75 M TRY |
| RECOVERY | 2.92 % | 5.02 M TRY |

Model training (LightGBM with two threads, SGD) and the simulation (seeded per customer) are deterministic on
one machine, but floating-point results can differ in the last digits across CPUs and library builds; that is
what the tolerances cover. The preset references come from the CLI run in docs/simulation.md; setup-fresh runs
them through the backend (`POST /api/scenarios/{id}/run`, baseline BASE), which uses the same engine and seeds.

## Provenance and caveats

- Table counts, model metrics and preset results were read from the generation report, the active artifact
  metadata (`ml-service/artifacts/models/*/<version>/metadata.json`) and docs/simulation.md. When this file was
  written Docker was paused, so the sample customer's balance (≈ 92,682 TRY, rounded) and the portfolio figures
  were taken from the UI/API of the same installation earlier that day, hence the 1 TRY tolerance.
- To refresh the references from a running installation:
  `ml-service\.venv\Scripts\python scripts\setup_steps.py snapshot` prints the current values in the JSON format;
  copy them into `reproducibility.json`.
- Not compared: wall-clock times, model version names (timestamps), run ids, `recorded_at` timestamps and the
  generation report's timing section.
