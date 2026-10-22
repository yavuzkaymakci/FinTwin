# FinTwin Architecture

> Status: domain model, synthetic data generator, core banking feed and event-sourced twin implemented; components
> marked *(planned)* do not exist in
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
        SIMO["Simulation orchestration (planned)"]
        AI["Spring AI assistants (planned)<br/>scenario assistant · customer coach"]
        MACRO["Macro data ingestion (planned)"]
        DOMAIN["Retail banking domain<br/>customers · accounts · loans · transactions"]
        GEN["Synthetic data generator<br/>(generate profile)"]
    end

    subgraph ML["ml-service · FastAPI (Python)"]
        MODELS["Behaviour models (planned)<br/>categorisation · cash flow · PD · churn"]
        SHAP["SHAP explanations (planned)"]
        SIM["Simulation engine (planned)<br/>Monte Carlo + agent-based"]
    end

    PG[("PostgreSQL 16<br/>event store + read models")]
    KAFKA[["Kafka (KRaft)"]]
    KUI[Kafka UI]
    EVDS[(TCMB EVDS API)]
    LLM[(LLM provider)]

    RA & PM & CU --> FE
    FE -- "REST /api, /actuator" --> API
    API --> TWIN & SIMO & AI
    TWIN -- "twin schema (JDBC)" --> PG
    DOMAIN -- "JPA / Flyway" --> PG
    GEN -- "JDBC batch insert + ground truth" --> PG
    FEED -- "reads public, writes feed.outbox" --> PG
    FEED -- "8 versioned topics" --> KAFKA
    KAFKA -- "batch listener" --> TWIN
    TWIN -. "twin-updated events (planned)" .-> KAFKA
    SIMO -- "REST: run scenario" --> SIM
    API -- "REST: score + explain" --> MODELS
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
| **ml-service** | Stateless compute: model inference, SHAP explanations, simulation runs. Trains on the public schema and validates against `ground_truth.*`. |
| **PostgreSQL** | Domain tables, event store, twin read models, macro time series, simulation results, and the `ground_truth` schema written by the generator. Schema owned by backend Flyway migrations. |
| **Kafka** | Transport of core banking events to the twin (8 topics + dead letter topics); later twin change events. |

## Key flows (target)

0. **Data generation** — the `generate` profile simulates the portfolio day by day and bulk-loads it, together with
   hidden states and labels in `ground_truth.*`.
1. **Twin update** — the core banking feed replays the outbox to Kafka → the twin appends each event to its event
   store and advances the customer's projection in sequence order (see the event flow below and
   [twin.md](twin.md)). Emitting `TwinUpdated` is planned.
2. **Scenario run** — analyst (directly or via the scenario assistant) defines macro shocks (FX, rate, inflation) →
   backend loads twin snapshots + EVDS baseline → ml-service simulation engine runs Monte Carlo paths →
   portfolio-level results (PD distribution, expected loss, churn) are stored and returned.
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

## Open decisions

- Simulation engine placement: proposed in ml-service (NumPy, close to the models); revisit if latency or
  orchestration needs push it into the backend.
- LLM provider for Spring AI.
