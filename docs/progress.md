# Progress notes

Short working notes so an interrupted session can resume. Newest first. Long-running jobs write to `logs/`
(gitignored); read the log to see how a job ended.

## Step 3 — Kafka-fed, event-sourced customer twin

Done (committed):
- `feat(generator)` d76c695 — opening balances, interest postings, linked transfer legs (V5); data regenerated.
- `feat(events)` e3753ad — versioned event contracts, 8 topics + `.DLT`.
- `feat(feed)` 89da3a7 — outbox (V6), bulk/live replay, admin API, consistency check.
- `feat(twin)` f9e9632 — event store, projection, snapshots/asOf, rebuild, DLT, metrics, API (V7); 115 tests green.

In progress:
1. Clean measurement run (reset twin, replay cursor, consumer group, Kafka topics) → bulk replay end to end,
   timing + throughput. Log: `logs/measure-*.log`.
2. Rebuild VERIFY/REPLACE on the full data set.
3. Consistency: ≥ 20 random customers' twin balances vs core banking; asOf spot checks.
4. Docs: `docs/twin.md` (feature dictionary), event flow diagram in `docs/architecture.md`, `CLAUDE.md`.
5. Final commits.

How to resume a measurement: see `scripts/measure-twin.sh` (writes `logs/measure-<timestamp>.log`).
