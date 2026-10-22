# Progress notes

Short working notes so an interrupted session can resume. Newest first. Long-running jobs write to `logs/`
(gitignored); read the log to see how a job ended.

## Step 4 — ML models (PD, churn, NLP) and backend integration

Done:
- V8 (8ad35bc): read-only roles `fintwin_ml` (no ground_truth) and `fintwin_ml_labels`; `twin.customer_risk_score`.
- e780b17: training data pipeline, point-in-time and ground-truth isolation tests.
- Models trained (logs `logs/train-risk-*.log`, `logs/train-nlp-*.log`, summary `python -m app.training.summary`):
  PD LightGBM test AUC 0.972 (LR 0.951), churn 0.761 (LR 0.748), NLP char+word macro F1 0.955 (held-out merchants
  0.722) vs rules 0.831 with MCC / 0.196 text only. Stress evaluation: Spearman(PD, hidden stress) 0.63.
- FastAPI: /v1/models/active, /v1/risk/score(/batch), /v1/risk/explain, /v1/categorize; 38 pytest green.

Next: backend `com.fintwin.twin.risk` (RestClient + Resilience4j, batch scoring, /api/twins/{id}/risk), then
docs/models.md, CLAUDE.md, final test runs.

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
