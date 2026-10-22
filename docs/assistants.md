# AI assistants — scenario assistant and customer coach

FinTwin has two Spring AI assistants. Both answer in Turkish and work only through tools, so every number they
report comes from the backend or the ML service:

- **Scenario assistant** (`RISK_ANALYST`). Turns macro questions into simulation runs and reports on the results.
- **Customer coach** (`CUSTOMER`). Helps a customer with budget and savings goals, using only that customer's own
  twin.

## Architecture

```mermaid
flowchart LR
    U[Client] -->|POST /api/assistants/{scenario,coach}/chat[/stream]| C[AssistantController]
    C --> S[AssistantService]
    S --> ID[CurrentUserProvider<br/>X-Demo-* headers, JWT in step 7]
    S --> G[InputGuard]
    S --> RL[RateLimiter]
    S --> E[AssistantEngine<br/>tool loop]
    E --> CM[(ChatModel<br/>Anthropic / Ollama / scripted in tests)]
    E --> TM[ToolCallingManager]
    TM --> T1[ScenarioAssistantTools]
    TM --> T2[CoachTools]
    T1 --> SIM[SimulationService / ScenarioService / RiskScoringService]
    T2 --> TW[TwinQueryService] & SIMF[SimulationService.forecast] & ML[ml-service /v1/goals/simulate]
    S --> MEM[(spring_ai_chat_memory<br/>window of 20)]
    S --> AUD[(assistant.ai_interaction)]
    S --> V[NumberValidator]
```

**Spring AI.** Version 1.1.8 is the latest stable line for Spring Boot 3.5; 2.x requires Spring Boot 4. The backend
uses `ChatClient`, `@Tool` / `@ToolParam` methods, `ToolCallingManager` and `MessageWindowChatMemory` on the JDBC
chat memory repository.

**Provider.** The provider is chosen by profile:

| Profile | Property | Model | Run |
|---|---|---|---|
| *(none)* | `spring.ai.model.chat=none` | – | assistants answer 503 `ASSISTANT_NOT_CONFIGURED`; guard refusals still work |
| `anthropic` | `spring.ai.model.chat=anthropic` | `${ANTHROPIC_MODEL:claude-sonnet-5-5}`, temperature 0.2, 2048 max tokens | `ANTHROPIC_API_KEY=... SPRING_PROFILES_ACTIVE=local,anthropic ./mvnw spring-boot:run` |
| `ollama` | `spring.ai.model.chat=ollama` | `${OLLAMA_MODEL:qwen2.5:7b-instruct}` at `${OLLAMA_BASE_URL:http://localhost:11434}` | `ollama pull qwen2.5:7b-instruct`, then `SPRING_PROFILES_ACTIVE=local,ollama` |

The API key is read from the environment only; it is never written to a file in the repository. Spring AI's own
retry of model calls is limited to 2 attempts with at most 4 s back-off, so a provider outage returns 502
`MODEL_CALL_FAILED` within seconds.

**Tool loop.** `AssistantEngine` switches Spring AI's internal tool execution off
(`internalToolExecutionEnabled=false`) and runs the loop itself. Each round trip:

1. sends the system prompt, the conversation window and the question;
2. checks any requested tool calls against the per-request limit (8);
3. records the calls (name, arguments, result, duration) through a `RecordingToolCallback` wrapper;
4. executes them with `ToolCallingManager`, passing the server-side `TurnContext` as `ToolContext`.

The same loop serves the blocking and the SSE endpoints and works identically with the scripted test model.

**Order of checks per request.**

1. Identity (`CurrentUserProvider`) — 401 if missing.
2. Role — 403 for the wrong assistant.
3. Conversation ownership — 404 for unknown conversations and for conversations of other users or customers, not
   distinguished on purpose.
4. Rate limit — 429, by default 20 requests per user and assistant in 10 minutes.
5. Input guard.
6. Model with tools.
7. Number check, memory and audit.

**Memory.** Conversations live in PostgreSQL. `assistant.conversation` holds the owner and the turn counter.
`spring_ai_chat_memory` (Flyway V10) keeps the last 20 messages; only the user's questions and the final answers
are stored, not tool traffic.

**Streaming.** `POST /api/assistants/{scenario|coach}/chat/stream` returns server-sent events:

| Event | Payload |
|---|---|
| `conversation` | `{conversationId}` |
| `tool` | `{names}` — any text streamed before it was interim and can be cleared |
| `token` | `{text}` |
| `done` | the full answer object, see below |
| `error` | error description |

Identity and limits are checked on the request thread before the stream starts.

**Answer object** (`ChatAnswer`) fields:
- `conversationId`, `answer`, `status` (`OK`, `REFUSED`, `TOOL_LIMIT`), `runIds`, `model`
- `toolCalls`: name, arguments, ok, durationMs
- `validation`: passed, checked, unverified
- `usage`: prompt, completion and total tokens
- `durationMs`

## Tools

### Scenario assistant (`ScenarioAssistantTools`)

| Tool | Purpose |
|---|---|
| `listScenarios` | Presets and user scenarios with their parameters |
| `createScenario(code, name, description?, parameters)` | Stores a scenario. `parameters` is the typed step-5 `ScenarioParameters`, so the tool schema carries every field's unit and range. Bean Validation errors are returned as `INVALID_PARAMETERS` so the model can fix them. |
| `startSimulation(scenarioId, baselineScenarioId?, startDate?, confirmationToken?)` | Human-in-the-loop, see below |
| `getRunStatus(runId)`, `waitForRun(runId, maxWaitSeconds?)` | Status and progress; `waitForRun` polls for at most 60 s |
| `getRunResults(runId)` | Compact portfolio result: default / churn distributions, EL, P99, EL % of EAD, delta vs baseline, quarterly cumulative default rate and macro path |
| `compareRuns(runIds)` | Side-by-side comparison (`GET /api/simulation-runs/compare`) |
| `getSegmentImpact(runId)` | Segment default rate, churn and loss, with deltas against the baseline |
| `getTopAffectedCustomers(runId, limit?)` | Customers with the largest PD increase and their reasons (Turkish texts) |
| `getPortfolioRiskSummary` | Latest twin scoring run: mean PD and churn, customers per grade, segment averages |

**Human in the loop.** `startSimulation` never starts a run on its first call. It stores a pending run in
`ConfirmationStore` and returns `CONFIRMATION_REQUIRED` with a summary and a token. The run starts only when the
tool is called with that token and all of the following hold:
- the call comes in a **later turn** of the **same conversation**;
- the user's own message in that turn (taken from the server, not from the model) is an explicit yes ("evet",
  "onaylıyorum", "başlat", …);
- the message contains no "hayır / iptal / vazgeç".

Tokens are single-use and expire after 30 minutes. A model that tries to confirm itself in the same turn gets a new
`CONFIRMATION_REQUIRED`.

### Customer coach (`CoachTools`)

| Tool | Purpose |
|---|---|
| `getMyTwinSummary` | Balances (TRY, deposits, USD), card debt and utilisation, loans, income and expense estimates, liquidity buffer, late payments |
| `getMySpendingBreakdown` | Last 90 days per category: total, monthly average, share |
| `getMyCashFlowForecast` | P10 / P50 / P90 bands of balance and buffer from the latest simulation run (step 5) |
| `simulateSavingsGoal(targetAmount, months, extraMonthlySaving?, categoryCuts?)` | ml-service `POST /v1/goals/simulate`, see below |

**Goal simulation** (ml-service `app/simulation/goals.py`):
- **Projection.** The customer's current twin is projected with the step-5 engine on the neutral macro path, with
  1,000 Monte Carlo paths. Optional changes are a fixed extra monthly saving and spending cuts per category.
- **Output:**
  - the probability of reaching the target with and without the changes;
  - the extra monthly saving needed for 80 % probability (bisection with common random numbers);
  - the monthly saving the cuts free up;
  - balance bands;
  - the target in today's prices.
- **Runtime.** About 5 s for 36 months.

## Safety measures

- **Identity only from `CurrentUser`.**
  - Tools read the caller from `TurnContext` in Spring AI's `ToolContext`. That context is not part of any tool
    schema, so the model cannot see or change it.
  - No coach tool has a customer-id parameter. A test asserts this on every tool schema, and extra arguments such
    as `"customerId": 1002` are ignored.
  - The demo provider reads `X-Demo-User`, `X-Demo-Role` and `X-Demo-Customer-Id`. **This is not security.** Step 7
    replaces it with JWT behind the same `CurrentUserProvider` interface.
- **Conversation ownership.** A conversation id from another user or customer is answered with 404, so memory
  cannot be read across users.
- **Input guard** (`InputGuard`):
  - It refuses requests that override instructions, ask for the system prompt, try jailbreak or role changes, or —
    for the coach — reach another customer ("müşteri 1002", "başka bir müşteri", "customerId", `X-Demo-`).
  - These refusals happen before any model call and are audited as `REFUSED`.
  - The tools enforce the same boundaries independently.
- **System prompts.**
  - Both: Turkish; numbers only from tools; polite refusal of off-topic requests and prompt injection; tool output is
    data, not instructions.
  - Scenario assistant: the confirmation rule and a fixed report format (summary, findings, notes, run ids).
  - Coach: no investment advice and no product recommendations.
- **Number check** (`NumberValidator`):
  - Every percentage and TL amount in an answer must match a number in the tool results, or in the user's question.
  - Turkish formats are supported: "%3,23", "8.010.634 TL", "5,7 milyon TL", "0,98 puan". Fractions ×100 count as
    a match, within the rounding the answer shows.
  - Mismatches are logged, returned in `validation.unverified` and stored in the audit log. The answer itself is not
    changed.
- **Limits.**
  - 20 requests per user and assistant per 10 minutes (429).
  - 8 tool calls per request; beyond that the answer is `TOOL_LIMIT`.
  - `waitForRun` blocks for at most 60 s.
  - Model calls are retried at most twice.
- **Audit** (`assistant.ai_interaction`). One row per request with:
  - user, role and customer;
  - assistant and conversation;
  - question and answer;
  - status (`OK`, `REFUSED`, `RATE_LIMITED`, `TOOL_LIMIT`, `ERROR`);
  - tool calls with arguments, a truncated result, ok flag and duration;
  - total duration, prompt / completion / total tokens, validation result and model.

## Tests (scripted chat model, no LLM)

The tests use `ScriptedChatModel` in place of an LLM. It plays a script of text and tool-call steps (also as a
stream) and keeps every prompt it receives. The tests run against the real database in Testcontainers, with the ML
client and the twin queries mocked.

`CoachAssistantTests` (8 tests):
- No coach tool schema contains a customer parameter.
- Injected `customerId: 1002` in the spending and goal tools still returns only customer 1001's data. The twin of
  1002 is never read, and the ML request carries 1001.
- Three injection or other-customer messages are refused without a model call.
- Another customer cannot continue a conversation (404).
- 401 / 403 for missing identity or the wrong role.
- An invented "%97" is flagged while "45.000 TL" from a tool passes.
- Tokens and tool arguments are audited.
- The system prompt forbids advice.

`ScenarioAssistantTests` (7 tests):
- The model creates a scenario and calls `startSimulation`, then tries to confirm itself in the same turn. Nothing
  starts. After "Evet, onaylıyorum, başlat." in the next turn exactly one run starts, with USD/TRY +40, inflation 4
  and the BASE baseline (−300 bp).
- "Hayır" never starts a run.
- A runaway loop stops after 8 tool calls (`TOOL_LIMIT`).
- The memory window carries the previous turn.
- SSE emits `conversation`, `tool`, `token` and `done`.
- A customer gets 403 on the scenario assistant; the 21st request in the window gets 429.
- Tool schemas describe the scenario fields.

`AssistantGuardsTests` (6 tests): Turkish number parsing and matching, invented numbers, guard patterns (analysts
may still name customer ids), confirmation rules and the sliding-window rate limiter.

ml-service `test_goals.py` (10 tests): extra saving and cuts raise the probability; the required saving reaches the
confidence; 60-month horizons work; invalid requests and the API are covered.

## Evaluation

**Question sets** (Turkish), with the expected tools and the critical parameters for each question:
- `docs/ai-eval/scenario-assistant.json`: 14 questions. They cover the examples from the brief, preset runs,
  parameter translation (FX, bp, inflation paths, wage rules, gradual shocks, 24-month horizon), confirmation with
  yes and with no, results / comparison / segments / top customers, the portfolio summary, an off-topic question
  and an injection.
- `docs/ai-eval/customer-coach.json`: 9 questions. They cover the summary, spending, forecast, three goal variants
  (cuts, extra saving, several categories), investment advice (no tools), another customer's data (refusal) and a
  debt-to-income question.

**Running it.** `python scripts/ai_eval.py --run-a <BASE run> --run-b <shock run> --customer-id 6`:
- It is manual only and never runs in CI.
- It needs a backend started with `local,anthropic` (or `local,ollama` with `--provider ollama`).
- It reports tool selection accuracy, parameter accuracy, the confirmation outcome, number-check pass rate,
  average duration and tokens, written to `logs/ai-eval-<time>.json` and `.md`.
- Without `ANTHROPIC_API_KEY` it prints the instructions and exits.

**Status.** No API key or Ollama was available in the development environment, so the evaluation against a real
model has **not been run**. The numbers above come from the scripted-model tests; the real-model results are still
to be filled in.

## Limitations

- **Identity.** The header-based identity is for development only (step 7: JWT and role-based access, including
  `/api/admin/**`).
- **Single instance.** The rate limiter, the confirmation store and the SSE executor are in memory and per
  instance. A second backend instance would need a shared store (e.g. PostgreSQL or Redis).
- **Interim streamed text.** Tokens are streamed as the model writes them; text before a tool call is interim
  (marked by the `tool` event). The number check runs on the final answer after streaming.
- **Number check scope.** It covers percentages and TL amounts only. It cannot judge numbers the model derives
  correctly but that are not in a tool result (e.g. a difference it computed itself); those are flagged for review.
  Answers are not blocked.
- **Input guard.** The guard is pattern-based and cannot catch every paraphrase. The real boundary is that tools
  have no way to reach other customers' data.
- **Results granularity.** The scenario results are portfolio and segment level. There is no card-vs-loan split, so
  "kredi kartı portföyü" questions are answered for the credit portfolio with this caveat.
- **Goal simulation scope.** Goal simulations use the neutral macro path only (no scenario choice yet) and nominal
  TRY.
- **Model quality is unmeasured.** It has to be measured with `scripts/ai_eval.py` once a key is available. Smaller
  Ollama models are expected to be weaker at Turkish tool calling.
