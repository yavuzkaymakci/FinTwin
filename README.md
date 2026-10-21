# FinTwin

**FinTwin** is a digital twin of a bank's retail customer portfolio. Each customer has a behavioural twin that is
continuously updated from transaction events (event sourcing over Kafka). Explainable ML models (spending
categorisation, cash-flow forecasting, probability of default, churn) and a Monte Carlo / agent-based simulation
engine let risk analysts stress the portfolio with macro shocks (FX, interest rates, inflation), while AI assistants
built on Spring AI run scenarios from natural language and coach customers on their personal finance goals.

Macro data comes from the Central Bank of the Republic of Türkiye (TCMB EVDS); customer and transaction data is
synthetic.

> Status: retail banking domain model and deterministic synthetic data generator in place; models, twins and
> simulation are next.

## Stack

| Part | Tech |
|------|------|
| `backend/` | Java 21, Spring Boot 3, Spring Data JPA, Spring Security, Flyway, Spring Kafka |
| `ml-service/` | Python 3.11, FastAPI |
| `frontend/` | React, TypeScript, Vite |
| `infra/` | Docker Compose: PostgreSQL 16, Kafka (KRaft), Kafka UI |

Architecture: [docs/architecture.md](docs/architecture.md).

## Prerequisites

- Docker with Docker Compose v2
- JDK 21 (Maven is provided via the wrapper)
- Python 3.11+
- Node.js 20+
- Optional: GNU make (on Windows via Git Bash or WSL)

## Running locally

```bash
# 1. Infrastructure: PostgreSQL, Kafka, Kafka UI (waits until healthy)
make up

# 2. Synthetic portfolio (5,000 customers x 24 months, ~11 M rows, ~2-3 min)
make generate

# 3. Backend -> http://localhost:8080/actuator/health, e.g. /api/customers/1
make backend-run

# 4. ML service -> http://localhost:8000/health (API docs at /docs)
make ml-install
make ml-run

# 5. Frontend -> http://localhost:5173
make frontend-install
make frontend-run
```

Kafka UI is available at http://localhost:8085. How the data is generated and what it looks like:
[docs/data-generation.md](docs/data-generation.md). Stop the infrastructure with `make down`.

Without make, see the command table in [CLAUDE.md](CLAUDE.md#development-commands).

## Tests

```bash
make test   # backend (JUnit), ml-service (pytest), frontend (Vitest)
```

Backend integration tests start PostgreSQL and Kafka with Testcontainers, so Docker must be running.
