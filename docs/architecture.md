# FinTwin Architecture

> Status: domain model, synthetic data generator, core banking feed, event-sourced twin, ML models (PD, churn,
> categorisation) and the simulation engine are implemented; components marked *(planned)* do not exist in
> code yet. This document is the target design and is updated as features land.

## Service overview

```mermaid
flowchart LR
    subgraph Users
        RA[Risk Analyst]
        PM[Product Manager]
        CU[Customer]
    end

    FE["frontend<br/>React + TS + Vite"]

    subgraph BE["backend · Spring Boot (Java 21)"]
        API[REST API + Security]
        TWIN["Customer twin<br/>event store · projection · snapshots"]
        FEED["Core banking feed<br/>outbox · bulk/live replay"]
        SIMO["Simulation orchestration<br/>scenarios · runs · forecasts"]
        RISK["Twin risk scoring"]
        AI["Spring AI assistants (planned)<br/>scenario assistant · customer coach"]
        MACRO["Macro data ingestion (planned)"]
        DOMAIN["Retail banking domain<br/>customers · accounts · loans · transactions"]
        GEN["Synthetic data generator<br/>(generate profile)"]
    end

    subgraph ML["ml-service · FastAPI (Python)"]
        MODELS["Behaviour models<br/>categorisation · PD · churn"]
        SHAP["SHAP explanations"]
        SIM["Simulation engine<br/>Monte Carlo · async jobs"]
    end

    PG[("PostgreSQL 16<br/>event store + read models")]
    KAFKA[["Kafka (KRaft)"]]
    KUI[Kafka UI]
    EVDS[(TCMB EVDS API)]
    LLM[(LLM provider)]

    RA & PM & CU --> FE
    FE -- "REST /api, /actuator" --> API
    API --> TWIN & SIMO & AI & RISK
    TWIN -- "twin schema (JDBC)" --> PG
    DOMAIN -- "JPA / Flyway" --> PG
    GEN -- "JDBC batch insert + ground truth" --> PG
    FEED -- "reads public, writes feed.outbox" --> PG
    FEED -- "8 versioned topics" --> KAFKA
    KAFKA -- "batch listener" --> TWIN
    TWIN -. "twin-updated events (planned)" .-> KAFKA
    SIMO -- "REST: submit + poll jobs" --> SIM
    RISK -- "REST: batch scoring" --> MODELS
    SIM -- "read-only (fintwin_ml): twin snapshots, macro" --> PG
    SIMO -- "scenarios, runs, forecasts" --> PG
    MODELS --- SHAP
    SIM --> MODELS
    AI -- "tool calls" --> SIMO
    AI --> LLM
    MACRO -- "HTTP" --> EVDS
    MACRO --> PG
    KUI --> KAFKA
```

## Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **frontend** | UI for the three roles; talks only to the backend (dev server proxies `/api` and `/actuator`). |
| **backend** | System of record. Owns the retail banking domain (customers, accounts, cards, loans, transactions, events), authentication/authorisation, customer twins (event-sourced from Kafka), macro data, scenario orchestration and the Spring AI assistants. Also hosts the deterministic synthetic data generator ([data-generation.md](data-generation.md)). |
| **ml-service** | Model training (offline, read-only roles), inference, SHAP explanations and the simulation engine (asynchronous jobs held in memory). Trains on the twin and public schemas; only `app/labeling` and `app/evaluation` may read `ground_truth.*`. See [models.md](models.md) and [simulation.md](simulation.md). |
| **PostgreSQL** | Domain tables, event store, twin read models, macro time series, simulation results, and the `ground_truth` schema written by the generator. Schema owned by backend Flyway migrations. |
| **Kafka** | Transport of core banking events to the twin (8 topics + dead letter topics); later twin change events. |

## Key flows (target)

0. **Data generation** — the `generate` profile simulates the portfolio day by day and bulk-loads it, together with
   hidden states and labels in `ground_truth.*`.
1. **Twin update** — the core banking feed replays the outbox to Kafka → the twin appends each event to its event
   store and advances the customer's projection in sequence order (see the event flow below and
   [twin.md](twin.md)). Emitting `TwinUpdated` is planned.
2. **Scenario run** — analyst (directly or via the scenario assistant, planned) picks or defines a scenario
   (FX, rate, inflation, wage rule) → backend stores the run and submits it with the BASE baseline → the ML
   service reads the twin snapshot and macro history, runs the Monte Carlo paths and scores them with the PD /
   churn models → the backend polls, stores the portfolio results and per-customer forecasts
   ([simulation.md](simulation.md)).
3. **Customer coaching** — customer asks the coach about a goal → assistant calls cash-flow forecast / simulation tools
   for that single twin → answer includes SHAP-based explanation.

## Event flow: core banking → twin

```mermaid
sequenceDiagram
    autonumber
    participant CB as core banking tables (public)
    participant OB as feed.outbox
    participant RP as ReplayService (bulk / live)
    participant K as Kafka (8 topics, key = customerId)
    participant L as TwinEventListener (batch)
    participant DB as twin schema
    participant DLT as <topic>.DLT

    CB->>OB: prepare: one INSERT … SELECT assigns position + customer_seq
    loop page of 10,000 outbox rows
        RP->>OB: read page after cursor (occurredAt < limit / simulated now)
        RP->>CB: load source rows per kind
        RP->>K: send versioned JSON events (idempotent producer)
        RP->>OB: persist cursor
    end
    K->>L: poll batch (≤ 20,000 records, all topics)
    alt valid records
        L->>DB: BEGIN
        L->>DB: INSERT event_store ON CONFLICT (event_id) DO NOTHING
        L->>DB: read unapplied events (customer_seq > last_seq)
        L->>DB: apply contiguous runs, write month-start snapshots
        L->>DB: UPDATE state WHERE version = ? (optimistic lock)
        L->>DB: COMMIT
        L->>K: commit offsets
    else invalid payload or retries exhausted
        L->>DLT: dead-letter the record
    end
```

## Decisions

- Synthetic data generator lives in the backend (not the ml-service): it writes the backend-owned schema in bulk and
  will later replay transactions to Kafka. See [data-generation.md](data-generation.md).
- Kafka payloads are JSON with versioned Java records (`...V1`, `schemaVersion` in every event; incompatible
  changes get a new record and `.v2` topic). No schema registry for now — revisit if non-JVM producers appear.
- The core banking feed publishes from a transactional-outbox table: ordering (global position, per-customer
  sequence) is fixed once in SQL, replays are resumable and republishing is idempotent. See [twin.md](twin.md).
- The twin applies events by per-customer sequence, not by arrival, because Kafka orders only within one
  partition of one topic; the event store is also the re-sequencing buffer.
- The twin reads neither the core banking tables nor `ground_truth` (enforced by `TwinIsolationTests`).

- The simulation engine lives in the ML service (vectorised NumPy next to the models); the backend owns
  scenarios and stores results, because ML jobs are in memory only. Models are applied as log-odds shifts
  against a neutral reference projection so the point-in-time PD is not double counted.

## Open decisions

- LLM provider for Spring AI.
