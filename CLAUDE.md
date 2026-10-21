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
- The frontend only talks to the backend; Vite proxies `/api` and `/actuator` in dev.
- Kafka topic auto-creation is disabled; topics are declared explicitly (e.g. `NewTopic` beans).
  Naming: `fintwin.<domain>.<event>.v<N>`, e.g. `fintwin.transactions.recorded.v1`.

## Repository layout

```
/backend      Spring Boot app (package root com.fintwin)
  src/main/java/com/fintwin/
    config/                 cross-cutting configuration (security, kafka, ...)
    <feature>/              one package per feature, layered inside:
      controller/           REST controllers (thin; map DTO <-> service calls)
      service/              business logic, transactions
      repository/           Spring Data repositories
      domain/               entities, value objects, domain events
      dto/                  request/response records
  src/main/resources/
    application.yml         shared config (default profile: local)
    application-local.yml   local dev, matches infra/docker-compose.yml
    db/migration/           Flyway migrations V<N>__<description>.sql
/ml-service   FastAPI app
  app/main.py               app factory
  app/api/                  routers (one module per area)
  app/<area>/               models, simulation, generator, ...
  tests/                    pytest
/frontend     Vite + React + TS
  src/api/                  typed API clients
  src/components/           components, tests colocated as *.test.tsx
/infra        docker-compose.yml, .env.example
/docs         architecture and design notes
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
- Tests: JUnit 5 + AssertJ/MockMvc. `@WebMvcTest`/`@DataJpaTest` slices for focused tests; `@SpringBootTest` for
  wiring. Current smoke tests use H2 (`test` profile); move DB/Kafka integration tests to Testcontainers when
  Docker is available.

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
| Test backend | `make backend-test` | `cd backend && ./mvnw test` |
| Install ML deps | `make ml-install` | `cd ml-service && python -m venv .venv && .venv/Scripts/python -m pip install -r requirements-dev.txt` |
| Run ML service | `make ml-run` | `cd ml-service && .venv/Scripts/python -m uvicorn app.main:app --reload --port 8000` |
| Test ML service | `make ml-test` | `cd ml-service && .venv/Scripts/python -m pytest` |
| Install frontend | `make frontend-install` | `cd frontend && npm install` |
| Run frontend | `make frontend-run` | `cd frontend && npm run dev` |
| Test / build frontend | `make frontend-test` / `make frontend-build` | `npm test` / `npm run build` |
| All tests | `make test` | |

Health checks: backend `http://localhost:8080/actuator/health`, ml-service `http://localhost:8000/health`,
Kafka UI `http://localhost:8085`.

## Current state and TODOs

- [x] Step 1 — scaffold (this state).
- [ ] Security: replace permit-all `SecurityConfig` with role-based access; restrict actuator health details
      (`show-details: always` is local-only convenience).
- [ ] Backend Dockerfile and adding backend/ml-service to compose.
- [ ] Testcontainers for PostgreSQL/Kafka integration tests.
