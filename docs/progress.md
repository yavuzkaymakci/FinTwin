# Progress notes

Short working notes so an interrupted session can resume. Newest first. Long-running jobs write to `logs/`
(gitignored); read the log to see how a job ended.

## Step 6 — Spring AI assistants

Done:
- 033f2cc ml-service `POST /v1/goals/simulate` (savings goal on the twin, extra saving, category cuts, required
  saving for 80 %); 77 pytest green.
- 5603b87 backend `com.fintwin.assistant`: Spring AI 1.1.8 (Boot 3.5 line), own tool loop, CurrentUser via
  ToolContext, confirmation gate, input guard, number check, rate limit, audit (V10), PostgreSQL memory, SSE;
  21 assistant tests with a scripted chat model.
- Live checks without a model: 401 / 403 / 503 `ASSISTANT_NOT_CONFIGURED`, guard refusals + SSE + audit rows;
  `ollama` profile without a server: 502 `MODEL_CALL_FAILED` in 1.2 s after bounding Spring AI's retry (it
  hung for minutes with the default 10 attempts).
- docs/assistants.md, docs/ai-eval/ (14 + 9 Turkish questions), scripts/ai_eval.py (manual; skips without key).

- Final runs: backend 166/166 (`logs/test-step6-20261022-122643.log`), pytest 77/77.

Not done: the real-LLM evaluation (no ANTHROPIC_API_KEY / Ollama on this machine) — see docs/assistants.md
"Evaluation" for how to run it.

## Step 5 — simulation engine

Done:
- ea4f8ea V9: `simulation` schema (scenario, simulation_run, customer_forecast), 5 presets, fintwin_ml grants.
- 56b6c00 ml-service `app/simulation/`: scenario schema, macro paths, vectorised engine, results, async jobs,
  `/v1/simulations` API, back-test and preset CLIs; 67 pytest green.
- Calibration findings (see docs/simulation.md): absolute re-scoring overshot the back-test (3.6 % vs 2.1 %),
  expense mean reversion (slope 0.34, measured) and reference-anchored log-odds hazards fixed it.
- Back-test 200 paths (`logs/backtest-20261022-104616.log`): 2025-09 sim 2.18 % vs obs 2.08 %, 2025-12 1.87 vs
  1.97, 2026-03 1.68 vs 1.63 (churn 2.98/2.85, 3.09/3.16, 3.02/2.81). 2026-04 crashed (no later snapshot) — fixed.
- d280c3a backend `com.fintwin.simulation` (scenario CRUD, runs, polling, compare, forecasts), shared client
  in `com.fintwin.ml`; cde2e9c back-test fix for the last start month.
- 2026-10-22 re-runs (CPU not shared; the first preset run was interrupted during RECOVERY):
  presets `logs/presets-20261022-111112.log` (BASE 3.23 %, FX 4.21 %, RATE 3.38 %, STAGFLATION 4.72 %, RECOVERY
  2.92 %; ~3 min and ~0.5 GB each), back-test incl. 2026-04 `logs/backtest-20261022-111112.log` (2026-04: sim
  1.72 % vs obs 1.32 %, static also over → model calibration at the data end).
- Churn analysis (`logs/churn-analysis-*.log`): churn's main driver (active loans) is static in the projection and
  the stress features lower churn in the model → documented as a limitation with improvement options.
- Live end-to-end via the backend: scenario SEVERE_FX created (duplicate 409, invalid 400), run 2 QUEUED →
  RUNNING → SUCCEEDED in 93 s, compare runs 1/2, 4,383 forecasts stored, customer 6 forecast OK, unknown 404.
  Note: curl from Git Bash on Windows sends non-UTF-8 for Turkish text; post JSON from a UTF-8 file.
- pytest 67/67 (ruff clean); backend 145/145 (`logs/test-final-20261022-114413.log`).

**Step 5 complete.** Next candidates: frontend (fan chart, scenario comparison), Spring AI scenario assistant (step
6) using `ScenarioParameters` as the tool schema, churn model macro sensitivity.

## Step 4 — ML models (PD, churn, NLP) and backend integration

Done:
- V8 (8ad35bc): read-only roles `fintwin_ml` (no ground_truth) and `fintwin_ml_labels`; `twin.customer_risk_score`.
- e780b17: training data pipeline, point-in-time and ground-truth isolation tests.
- Models trained (logs `logs/train-risk-*.log`, `logs/train-nlp-*.log`, summary `python -m app.training.summary`):
  PD LightGBM test AUC 0.972 (LR 0.951), churn 0.761 (LR 0.748), NLP char+word macro F1 0.955 (held-out merchants
  0.722) vs rules 0.831 with MCC / 0.196 text only. Stress evaluation: Spearman(PD, hidden stress) 0.63.
- FastAPI: /v1/models/active, /v1/risk/score(/batch), /v1/risk/explain, /v1/categorize; 38 pytest green.

- 9f4a53f: backend `com.fintwin.twin.risk` (RestClient + Resilience4j retry/circuit breaker, portfolio batch
  scoring, `/api/twins/{id}/risk`); 15 new tests (MockRestServiceServer, Testcontainers, WebMvc) green.
- 2026-10-22 live check: 5,000 twins scored in 3.9 s; customer 6 PD 1.40 % grade 5 (reasons in
  `logs/sample-backend-risk.json`); with ml-service stopped: 503 `ML_SERVICE_UNAVAILABLE`, circuit opens after
  the first retried failures (then 503 in ~5 ms), stored scores still served, backend health UP.
- docs/models.md (model cards), CLAUDE.md, Makefile targets `ml-export-labels ml-train ml-train-nlp ml-evaluate`.

- Final runs: pytest 38/38 + ruff clean (`logs/pytest-*.log`), backend 130/130 (`logs/test-20261022-100733.log`).

**Step 4 complete.** Next candidates: `TwinUpdated` events for event-driven scoring, NLP model in the twin,
security for `/api/admin/**`, model monitoring (PSI).

Re-create artifacts (gitignored) after a fresh checkout: `make ml-export-labels ml-train ml-train-nlp ml-evaluate`.

## Step 3 — Kafka-fed, event-sourced customer twin

Done (committed):
- `feat(generator)` d76c695 — opening balances, interest postings, linked transfer legs (V5); data regenerated.
- `feat(events)` e3753ad — versioned event contracts, 8 topics + `.DLT`.
- `feat(feed)` 89da3a7 — outbox (V6), bulk/live replay, admin API, consistency check.
- `feat(twin)` f9e9632 — event store, projection, snapshots/asOf, rebuild, DLT, metrics, API (V7); 115 tests green.

- 2026-10-22: clean measurement (`logs/measure-20261022-083120.log`): publish 262 s (21.7k ev/s),
  twin complete after 397 s (14.3k ev/s end to end), 5,000 twins, 99,693 snapshots, 0 DLT. Docs twin.md,
  architecture.md, CLAUDE.md committed. Backend left running from the measurement script.

- 2026-10-22: verification (`logs/verify-20261022-083849.log`): rebuild VERIFY/REPLACE 0 mismatches, identical
  checksums; 11,247/11,247 accounts match; 20 random customers OK; as-of 15/15 OK. Results in docs/twin.md.

- 2026-10-22: the previous test run crashed the laptop (memory). Bounded all JVM heaps and container memory (see
  CLAUDE.md "Memory budget"), stopped the compose stack during tests: full suite 115/115 green in 52 s
  (`logs/test-20261022-091922.log`).

**Step 3 complete.** Next candidates (see CLAUDE.md TODOs): role-based security for `/api/admin/**`,
`TwinUpdated` events for ml-service scoring, NLP categorisation replacing `TransactionClassifier`.

Resume: `scripts/measure-twin.sh` (clean timed replay), then `scripts/verify-twin.sh` (rebuild + consistency +
as-of; writes `logs/verify-<timestamp>.log`). Results go into the results section of `docs/twin.md`.
