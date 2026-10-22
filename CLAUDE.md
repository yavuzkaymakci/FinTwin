# CLAUDE.md — FinTwin

This file is the shared reference for every development step. Read it before making changes and keep it up to date
when architecture, conventions or commands change.

## Vision

FinTwin is a **digital twin of a bank's retail customer portfolio**.

- **Behavioural twins**: every customer has a behavioural twin that is continuously updated from transaction events
  (event sourcing over Kafka).
- **Behaviour models** run in the Python ML service and are explainable with SHAP:
  spending categorisation, cash-flow forecasting, probability of default (PD), churn.
- **Simulation engine** (Monte Carlo + agent-based) applies macro shocks (FX, interest rate, inflation) to the twins
  and produces portfolio-level outcomes.
- **Two Spring AI assistants**:
  - *Scenario assistant* for risk analysts — runs scenarios from natural language.
  - *Customer coach* for customers — simulates personal finance goals.
- **Data**: macro data from **TCMB EVDS**; customer and transaction data from a realistic **synthetic generator**.
- **Roles**: `RISK_ANALYST`, `PRODUCT_MANAGER`, `CUSTOMER`.

## Architecture

See [docs/architecture.md](docs/architecture.md) for the diagram and flows.

| Service | Tech | Port |
|---------|------|------|
| backend | Java 21, Spring Boot 3.5 (Maven), Web, Data JPA, Security, Flyway, Spring Kafka, Actuator | 8080 |
| ml-service | Python 3.11+, FastAPI, pip + requirements.txt | 8000 |
| frontend | React 18 + TypeScript + Vite, Vitest | 5173 |
| postgres | PostgreSQL 16 (Docker) | 5432 |
| kafka | Confluent cp-kafka 7.9, KRaft single node, no ZooKeeper (Docker) | 9092 (host), 29092 (in-network) |
| kafka-ui | kafbat/kafka-ui (Docker) | 8085 |

- The backend is the system of record and owns the database schema (Flyway). The ml-service is stateless compute.
- The synthetic data generator lives in the backend (`com.fintwin.generator`, `generate` profile) and also writes the
  `ground_truth` schema (hidden variables and labels). Models train on the public schema only and use
  `ground_truth.*` for labels/validation. Details and the current data report: [docs/data-generation.md](docs/data-generation.md).
- The **core banking feed** (`com.fintwin.feed`) replays the generated data (public schema = core banking system)
  to Kafka from a transactional-outbox table (`feed.outbox`). The **customer twin** (`com.fintwin.twin`) is fed only
  by these events: event store, projection, monthly snapshots, as-of and rebuild in the `twin` schema.
  Design, event schemas and the feature dictionary: [docs/twin.md](docs/twin.md).
- The frontend only talks to the backend; Vite proxies `/api` and `/actuator` in dev.
- Kafka topic auto-creation is disabled; topics are declared explicitly (e.g. `NewTopic` beans).
  Naming: `fintwin.<domain>.<event>.v<N>`, e.g. `fintwin.transactions.recorded.v1`.

## Repository layout

```
/backend      Spring Boot app (package root com.fintwin)
  src/main/java/com/fintwin/
    config/                 cross-cutting configuration (security, kafka, ...)
    common/dto/             shared DTOs (PageResponse)
    customer/ account/ loan/ merchant/ transaction/ macro/
                            retail banking domain features
    events/                 Kafka event contracts: CoreBankingEvent, v1/ records, Topics, EventCodec
    feed/                   core banking feed: outbox/ (prepare, cursor), publish/ (ReplayService, ReplayClock,
                            EventAssembler), consistency/ (twin vs core balance check), controller/
    twin/                   customer twin: consumer/ (batch listener, error handling), store/ (event store, state,
                            snapshots), projection/ (pure TwinProjector, TwinFeatureCalculator, classifier),
                            service/ (projection tx, queries/as-of, rebuild), controller/
    generator/              synthetic data generator (not a layered feature):
      simulation/             pure, deterministic day-by-day customer simulation + StressModel
      profile/ catalog/ calendar/ macro/ installment/ text/ rng/
                              segment parameters, merchants/cities, Turkish calendar, macro path,
                              annuity schedules, statement-text noise, seeded random streams
      writer/                 JdbcTemplate batch writer;  report/  markdown statistics report
    <feature>/              one package per feature, layered inside:
      controller/           REST controllers (thin; map DTO <-> service calls)
      service/              business logic, transactions
      repository/           Spring Data repositories
      domain/               entities, value objects, domain events
      dto/                  request/response records
  src/main/resources/
    application.yml         shared config (default profile: local)
    application-local.yml   local dev, matches infra/docker-compose.yml
    application-generate.yml  generator settings (combine with local: profiles local,generate)
    db/migration/           Flyway migrations V<N>__<description>.sql (V2 domain, V3 reference data, V4 ground truth,
                            V5 feed support columns, V6 feed outbox, V7 twin)
/ml-service   FastAPI app
  app/main.py               app factory
  app/api/                  routers (one module per area)
  app/<area>/               models, simulation, generator, ...
  tests/                    pytest
/frontend     Vite + React + TS
  src/api/                  typed API clients
  src/components/           components, tests colocated as *.test.tsx
/infra        docker-compose.yml (Postgres tuned for bulk loads), .env.example
/scripts      measure-twin.sh (clean timed end-to-end replay)
/docs         architecture.md, data-generation.md, twin.md, progress.md (resume notes)
/logs         output of long-running jobs (gitignored)
```

## Coding rules

General
- **All code, identifiers, comments, commit messages and docs in English.**
- Every feature ships with tests. No feature PR/commit without at least one test covering its behaviour.
- Small, focused commits using Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`).
- No secrets in the repo; configuration via environment variables with local defaults.

Backend (Java)
- **No Lombok.** Use `record`s for DTOs, events and value objects; write explicit constructors/getters for JPA entities.
- Layered architecture per feature: `controller` → `service` → `repository`, with `domain` for the model.
  Controllers never touch repositories directly; entities never leave the service layer (map to DTO records).
- Constructor injection only (no field `@Autowired` in production code).
- Schema changes only through new Flyway migrations; never edit an applied migration. `ddl-auto` stays `validate`.
- Money as `BigDecimal` (never `double`); timestamps as `Instant`/`OffsetDateTime` in UTC.
- Tests: JUnit 5 + AssertJ/MockMvc. `@WebMvcTest` (+ `@Import(SecurityConfig.class)`) for controllers;
  `@RepositoryTest` (= `@DataJpaTest` on the real PostgreSQL schema) for repositories; `@SpringBootTest` +
  `@Import(TestcontainersConfiguration.class)` for wiring/integration. PostgreSQL and Kafka run in Testcontainers
  (shared JVM-wide singletons wired via `DynamicPropertyRegistrar`; do not turn them into beans — closing one cached
  context would stop them for the others). Docker must be running for `mvnw test`. Tests that write generated data
  must clean up (`JdbcBatchWriter.truncateGeneratedTables()`), the database is shared across test classes.
- Bulk data goes through `JdbcTemplate.batchUpdate` (or `unnest` array inserts), never JPA `saveAll`.
- The twin listener is disabled in the `test` profile (cached contexts would split the consumer group's partitions);
  tests that need it enable it explicitly (`fintwin.twin.consumer.enabled=true`).

Events and twin (Java)
- **The twin never reads the public (core banking) schema or `ground_truth`**; only `com.fintwin.events` and the
  `twin` schema. `TwinIsolationTests` enforces it. Cross-checks against core banking belong in `feed.consistency`.
- Event records are immutable contracts: add optional fields only; anything else is a new `...V2` record on a new
  `.v2` topic. Keep `eventId` deterministic (`EventCodec.eventId`) so republishing stays idempotent.
- Customer events are applied in `customerSequence` order, never by arrival. The projection
  (`TwinProjector`, `TwinFeatureCalculator`) must stay pure and deterministic and evaluate features at event time,
  never wall-clock time — live projection, rebuild and as-of must agree exactly (rebuild `VERIFY` = 0 mismatches).
- Do not publish label tables (`customer_event`) to the twin.

Generator (Java, `com.fintwin.generator`)
- **Determinism is a hard requirement**: same seed + settings ⇒ identical data. Draw randomness only from the
  per-customer `Rand` obtained via `SeededRandoms` (never `Math.random`, `new Random()`, `ThreadLocalRandom`, JDK
  `nextGaussian`, `now()`, or iteration over hash-ordered collections). Changing stream salts, the order of draws,
  or default parameters changes the data set — re-run `make generate` and commit the updated report.
- Money inside the simulation is `long` minor units (kuruş/cent); convert with `Sim.toMoney` when writing.
- Events must come from the causal model (`StressModel`), not be sampled directly; keep hidden state in
  `ground_truth.*`. After changing behaviour, check the calibration targets in docs/data-generation.md
  (24-month default 2–5 %, churn 8–15 %, ≥1 late payment 10–25 %, default monotonic in stress decile).

ML service (Python)
- Type hints everywhere; Pydantic models for request/response schemas.
- Keep routers thin; put logic in `app/<area>/` modules.
- Lint with `ruff`, test with `pytest`. Pin dependencies in `requirements*.txt`.
- Model artifacts are not committed (`ml-service/artifacts/` is gitignored).

Frontend (TypeScript)
- Strict TypeScript; no `any`.
- Function components + hooks. API calls go through `src/api/` typed clients.
- Tests with Vitest + Testing Library, colocated next to the component.

## Development commands

Make targets (need GNU make + POSIX shell; on Windows use Git Bash/WSL — or run the underlying commands directly):

| Task | Make | Direct command |
|------|------|----------------|
| Start infra | `make up` | `docker compose -f infra/docker-compose.yml up -d --wait` |
| Stop infra | `make down` | `docker compose -f infra/docker-compose.yml down` |
| Run backend | `make backend-run` | `cd backend && ./mvnw spring-boot:run` (Windows: `mvnw.cmd`) |
| Test backend (needs Docker) | `make backend-test` | `cd backend && ./mvnw test` |
| Generate synthetic data | `make generate` | `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,generate` |
| Install ML deps | `make ml-install` | `cd ml-service && python -m venv .venv && .venv/Scripts/python -m pip install -r requirements-dev.txt` |
| Run ML service | `make ml-run` | `cd ml-service && .venv/Scripts/python -m uvicorn app.main:app --reload --port 8000` |
| Test ML service | `make ml-test` | `cd ml-service && .venv/Scripts/python -m pytest` |
| Install frontend | `make frontend-install` | `cd frontend && npm install` |
| Run frontend | `make frontend-run` | `cd frontend && npm run dev` |
| Test / build frontend | `make frontend-test` / `make frontend-build` | `npm test` / `npm run build` |
| All tests | `make test` | |

Health checks: backend `http://localhost:8080/actuator/health`, ml-service `http://localhost:8000/health`,
Kafka UI `http://localhost:8085`, metrics `http://localhost:8080/actuator/metrics` and `/actuator/prometheus`.
Read API: `GET /api/customers/{id}`, `GET /api/customers/{id}/transactions?from=&to=`,
`GET /api/macro-indicators?indicator=`. Twin API: `GET /api/twins/{id}[?asOf=]`, `/events?from=&to=`,
`/history?feature=`.

Feed and twin workflow (backend running, `local` profile):

| Task | Command |
|------|---------|
| Build outbox | `curl -X POST localhost:8080/api/admin/replay/prepare` |
| Bulk / live replay | `curl -X POST localhost:8080/api/admin/replay/bulk[?until=2025-06-30]` / `.../live?secondsPerDay=5` |
| Replay status, stop, speed, rewind | `GET .../replay/status`, `POST .../replay/stop`, `.../speed?secondsPerDay=`, `.../reset` |
| Twin vs core balances | `GET .../replay/consistency?sample=20` |
| Rebuild twin | `curl -X POST "localhost:8080/api/admin/twins/rebuild?mode=VERIFY"` (or `REPLACE`) |
| Wipe twin (after `make generate`) | `curl -X POST localhost:8080/api/admin/twins/reset` |
| Clean timed measurement | `nohup scripts/measure-twin.sh > logs/measure-$(date +%Y%m%d-%H%M%S).log 2>&1 &` |

Long-running jobs (bulk replay, rebuild, full test suite, generation) run detached with output in `logs/`; progress
and resume notes are kept in [docs/progress.md](docs/progress.md).

Generator overrides: `GENERATOR_CUSTOMERS`, `GENERATOR_MONTHS`, `GENERATOR_SEED`, `GENERATOR_END_MONTH`,
`GENERATOR_RESET`, `GENERATOR_REPORT_PATH` (default 5000 / 24 / 42 / 2026-09 / true / `../docs/data-generation.md`).
Full run: ~11 M rows in ~2–3 min; it truncates previously generated tables (reference data is kept).

Memory budget (16 GB laptop, WSL2 capped at 6 GB + 4 GB swap) — keep these limits when adding services or tests:

| Process | Limit | Where |
|---------|-------|-------|
| Maven JVM | `-Xmx512m` | `backend/.mvn/jvm.config` |
| Test JVM (single fork) | `-Xmx1g -XX:MaxMetaspaceSize=384m` | surefire `argLine` in `backend/pom.xml` |
| Testcontainers Postgres / Kafka | 768 MB each, Kafka `KAFKA_HEAP_OPTS=-Xms256m -Xmx512m` | `TestcontainersConfiguration` |
| App via `spring-boot:run` | `-Xmx1536m` | spring-boot-maven-plugin `jvmArguments` |
| Compose Postgres / Kafka / Kafka UI | 1.5 GB / 1.5 GB (`-Xmx1g`) / 512 MB (`-Xmx256m`) | `infra/docker-compose.yml` |

Stop the compose stack before `mvnw test` (`docker compose -f infra/docker-compose.yml stop`; tests use their own
containers) and start it again afterwards; never run the test suite and a replay/generation at the same time.

Windows note: if `java -version` shows an old JRE, the shell's PATH predates the JDK 21 install — set
`JAVA_HOME` to the Temurin 21 directory and prepend `$JAVA_HOME/bin` (and Docker's `resources/bin`) to PATH.

## Current state and TODOs

- [x] Step 1 — scaffold.
- [x] Step 2 — retail banking domain model (V2–V4), read API, deterministic synthetic data generator with causal
      stress model and ground truth, report in docs/data-generation.md.
- [x] Docker compose verified (postgres, kafka, kafka-ui healthy).
- [x] Testcontainers for PostgreSQL/Kafka integration tests (H2 removed; Testcontainers 1.21.4 for Docker Engine 29).
- [ ] Security: replace permit-all `SecurityConfig` with role-based access; restrict actuator health details
      (`show-details: always` is local-only convenience).
- [ ] Backend Dockerfile and adding backend/ml-service to compose.
- [ ] TCMB EVDS ingestion to replace the seeded `macro_indicator` rows.
- [x] Step 3 — core banking feed (outbox, bulk/live replay) and Kafka-fed event-sourced customer twin (event store,
      projection, snapshots/as-of, rebuild, idempotency, DLT, metrics, API), docs/twin.md.
- [ ] Publish `TwinUpdated` events for downstream consumers (ml-service scoring).
- [ ] Restrict `/api/admin/**` (replay, rebuild, reset) to an admin role once security lands.
- [ ] Card limit history: the feed publishes the final credit limit only (the generator does not record changes).
- [ ] Replace the twin's rule-based `TransactionClassifier` with the NLP categorisation model.
