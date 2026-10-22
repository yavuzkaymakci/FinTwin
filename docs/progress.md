# Progress notes

Short working notes so an interrupted session can resume. Newest first. Long-running jobs write to `logs/`
(gitignored); read the log to see how a job ended.

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
