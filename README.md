# FinTwin

**FinTwin** is a digital twin of a bank's retail customer portfolio. Each customer has a behavioural twin that is
continuously updated from transaction events (event sourcing over Kafka). Explainable ML models (spending
categorisation, cash-flow forecasting, probability of default, churn) and a Monte Carlo / agent-based simulation
engine let risk analysts stress the portfolio with macro shocks (FX, interest rates, inflation), while AI assistants
built on Spring AI run scenarios from natural language and coach customers on their personal finance goals.

Macro data comes from the Central Bank of the Republic of Türkiye (TCMB EVDS); customer and transaction data is
synthetic.

> Status: domain model, synthetic data, Kafka-fed twins, PD / churn / categorisation models, the simulation
> engine, the two Spring AI assistants and the web UI are in place; security (JWT) is next.

## Stack

| Part | Tech |
|------|------|
| `backend/` | Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security, Flyway, Spring Kafka, Spring AI 1.1 |
| `ml-service/` | Python 3.11, FastAPI |
| `frontend/` | React 18, TypeScript, Vite, React Router, TanStack Query, Recharts, Tailwind CSS + Radix (shadcn-style) |
| `infra/` | Docker Compose: PostgreSQL 16, Kafka (KRaft), Kafka UI |

Architecture: [docs/architecture.md](docs/architecture.md).

## Prerequisites

- Docker with Docker Compose v2
- JDK 21 (Maven is provided via the wrapper)
- Python 3.11+
- Node.js 20+
- Optional: GNU make (on Windows via Git Bash or WSL)

## One-click start on Windows

From `cmd` (no PowerShell needed), after the one-time setup below (data generated, `ml-service\.venv` created,
models trained):

```bat
rem ai-demo profile: scripted assistant answers, real tool calls
scripts\start-all.bat
rem real Claude model (needs: set ANTHROPIC_API_KEY=sk-ant-...)
scripts\start-all.bat anthropic
rem local Ollama model (ollama serve, model pulled)
scripts\start-all.bat ollama
rem stop the windows and the containers, keeping all data
scripts\stop-all.bat
```

`start-all.bat` checks that Docker Desktop is running, starts the compose stack and waits until it is healthy,
opens backend, ml-service and frontend in their own cmd windows ("FinTwin backend", ...), waits for the backend
and opens http://localhost:5173. Swagger UI: http://localhost:8080/swagger-ui/index.html.

## Web UI

Turkish UI, tr-TR number/money/date formats, light and dark theme. The role selector in the header switches
between *Risk Analisti* and *Müşteri* (with the customer number to view as); it sets the development identity
headers in one place (`frontend/src/auth/identity.ts`) until JWT replaces them.

- Risk analyst: portfolio dashboard, customer list, customer twin detail (time travel over the event-sourced
  twin, risk reasons, feature history, cash-flow fan chart), scenarios and runs (results, comparison), scenario
  assistant chat, live feed, system and models.
- Customer: own summary (balances, spending, forecast) and the finance coach chat with goal cards.

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

## AI assistants

The scenario assistant (risk analysts) and the customer coach answer in Turkish through Spring AI. The chat
model is chosen with a profile; without one the assistant endpoints answer 503 and the UI explains how to enable
a provider. The `ai-demo` profile (default of `start-all.bat`) plugs in a scripted chat model that calls the real
tools, so tool tags, the confirmation card and run links show real data; the UI labels it "Demo modu" and the
profile refuses to start together with a real provider.

```bash
# Anthropic Claude: the key comes from the environment only, never from a file in the repository
export ANTHROPIC_API_KEY=sk-ant-...          # optional: export ANTHROPIC_MODEL=claude-sonnet-5-5
cd backend && SPRING_PROFILES_ACTIVE=local,anthropic ./mvnw spring-boot:run

# or a local Ollama model with tool calling
ollama pull qwen2.5:7b-instruct
cd backend && SPRING_PROFILES_ACTIVE=local,ollama ./mvnw spring-boot:run   # OLLAMA_MODEL / OLLAMA_BASE_URL

# chat (development identity headers until JWT arrives in step 8)
curl -X POST localhost:8080/api/assistants/coach/chat -H 'Content-Type: application/json' \
  -H 'X-Demo-User: alice' -H 'X-Demo-Role: CUSTOMER' -H 'X-Demo-Customer-Id: 6' \
  --data-binary @question.json     # {"message": "3 yılda 500 bin TL biriktirebilir miyim?"}
```

Tools, safety measures and the evaluation (`python scripts/ai_eval.py`): [docs/assistants.md](docs/assistants.md).

## Tests

```bash
make test   # backend (JUnit), ml-service (pytest), frontend (Vitest)
```

Backend integration tests start PostgreSQL and Kafka with Testcontainers, so Docker must be running.
