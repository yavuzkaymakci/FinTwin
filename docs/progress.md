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

In progress:
2. Rebuild VERIFY/REPLACE on the full data set.
3. Consistency: ≥ 20 random customers' twin balances vs core banking; asOf spot checks.
4. Docs: `docs/twin.md` (feature dictionary), event flow diagram in `docs/architecture.md`, `CLAUDE.md`.
5. Final commits.

Resume: `scripts/measure-twin.sh` (clean timed replay), then `scripts/verify-twin.sh` (rebuild + consistency +
as-of; writes `logs/verify-<timestamp>.log`). Results go into the results section of `docs/twin.md`.
