package com.fintwin.assistant.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fintwin.assistant.core.AssistantProperties;
import com.fintwin.assistant.core.ConfirmationStore;
import com.fintwin.assistant.core.TurnContext;
import com.fintwin.assistant.user.Role;
import com.fintwin.ml.MlServiceUnavailableException;
import com.fintwin.simulation.dto.RunComparison;
import com.fintwin.simulation.dto.RunRequest;
import com.fintwin.simulation.dto.ScenarioParameters;
import com.fintwin.simulation.dto.ScenarioRequest;
import com.fintwin.simulation.dto.ScenarioView;
import com.fintwin.simulation.dto.SimulationRunView;
import com.fintwin.simulation.service.ScenarioService;
import com.fintwin.simulation.service.SimulationNotFoundException;
import com.fintwin.simulation.service.SimulationService;
import com.fintwin.twin.risk.service.RiskScoringService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tools of the scenario assistant (risk analysts). Results are compact JSON so they fit the model's context; every
 * tool checks the caller's role from the {@link TurnContext}, not from arguments.
 */
@Component
public class ScenarioAssistantTools {

    private static final Set<String> FINISHED = Set.of("SUCCEEDED", "FAILED");

    private final ScenarioService scenarios;
    private final SimulationService simulations;
    private final RiskScoringService risk;
    private final ConfirmationStore confirmations;
    private final Validator validator;
    private final AssistantProperties properties;
    private final ObjectMapper json;

    public ScenarioAssistantTools(ScenarioService scenarios, SimulationService simulations, RiskScoringService risk,
                                  ConfirmationStore confirmations, Validator validator, AssistantProperties properties,
                                  ObjectMapper json) {
        this.scenarios = scenarios;
        this.simulations = simulations;
        this.risk = risk;
        this.confirmations = confirmations;
        this.validator = validator;
        this.properties = properties;
        this.json = json;
    }

    @Tool(name = "listScenarios", description = "Lists the stored macro scenarios (presets and user-defined) with "
            + "their id, code, name, description and parameters.")
    public Object listScenarios(ToolContext context) {
        return guarded(context, () -> scenarios.list().stream().map(this::scenario).toList());
    }

    @Tool(name = "createScenario", description = "Creates a new user-defined macro scenario from typed parameters. "
            + "This only stores the scenario; it does not run a simulation. Returns the new scenarioId, or "
            + "INVALID_PARAMETERS with the violated constraints so you can fix them.")
    public Object createScenario(
            @ToolParam(description = "Unique code: upper-case letters, digits and _, 2-64 chars, e.g. USD40_INF4") String code,
            @ToolParam(description = "Short Turkish name, e.g. 'Dolar +%40, enflasyon %4'") String name,
            @ToolParam(description = "One-sentence description of the assumptions", required = false) String description,
            @ToolParam(description = "Scenario parameters; percentages as numbers (usdTryChangePct 40 = +40 %), "
                    + "monthlyInflationPct per month in percent") ScenarioParameters parameters,
            ToolContext context) {
        return guarded(context, () -> {
            ScenarioRequest request = new ScenarioRequest(code, name, description, parameters);
            Set<ConstraintViolation<ScenarioRequest>> violations = validator.validate(request);
            if (!violations.isEmpty()) {
                return Map.of("error", "INVALID_PARAMETERS", "violations", violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList());
            }
            return scenario(scenarios.create(request));
        });
    }

    @Tool(name = "startSimulation", description = "Starts a Monte Carlo simulation run of a scenario against a "
            + "baseline (default: the BASE preset). Expensive (minutes). The first call never starts anything: it "
            + "returns CONFIRMATION_REQUIRED with a summary and a confirmationToken. Show that summary to the user "
            + "and ask for explicit approval. Only after the user approves in their next message, call again with the "
            + "same arguments and the confirmationToken. Returns STARTED with the runId.")
    public Object startSimulation(
            @ToolParam(description = "Id of the scenario to simulate") long scenarioId,
            @ToolParam(description = "Id of the baseline scenario; omit for the BASE preset", required = false)
            Long baselineScenarioId,
            @ToolParam(description = "Twin snapshot to start from, first day of a month (yyyy-MM-01); omit for the "
                    + "current portfolio", required = false) String startDate,
            @ToolParam(description = "Token from the CONFIRMATION_REQUIRED answer; only after the user approved",
                    required = false) String confirmationToken,
            ToolContext context) {
        return guarded(context, () -> {
            TurnContext turn = TurnContext.of(context);
            ScenarioView scenario = scenarios.get(scenarioId);
            Long baseline = baselineScenarioId;
            if (baseline != null) {
                scenarios.get(baseline);
            }
            LocalDate start = startDate == null || startDate.isBlank() ? null : LocalDate.parse(startDate.trim());
            if (confirmationToken != null && !confirmationToken.isBlank()) {
                var confirmed = confirmations.confirm(confirmationToken, turn)
                        .filter(p -> p.scenarioId() == scenarioId);
                if (confirmed.isPresent()) {
                    var p = confirmed.get();
                    SimulationRunView run = simulations.start(scenarioId,
                            new RunRequest(p.startDate(), p.baselineScenarioId(), null, null));
                    turn.addRunId(run.id());
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("status", "STARTED");
                    out.put("runId", run.id());
                    out.put("scenarioCode", run.scenarioCode());
                    out.put("runStatus", run.status());
                    out.put("baselineScenarioId", run.baselineScenarioId());
                    out.put("note", "Runs take about 2-4 minutes; use getRunStatus / waitForRun.");
                    return out;
                }
            }
            var pending = confirmations.issue(turn, scenarioId, baseline, start);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "CONFIRMATION_REQUIRED");
            out.put("confirmationToken", pending.token());
            if (confirmationToken != null && !confirmationToken.isBlank()) {
                out.put("reason", "The token is not valid for this turn: the user has not explicitly approved in a "
                        + "later message. A new token was issued.");
            }
            out.put("scenario", scenario(scenario));
            out.put("baseline", baseline == null ? "BASE preset" : scenario(scenarios.get(baseline)));
            out.put("startDate", start == null ? "current portfolio" : start.toString());
            out.put("instruction", "Summarise the parameters in Turkish and ask the user to approve. Do not call "
                    + "startSimulation with the token before the user's approval.");
            return out;
        });
    }

    @Tool(name = "getRunStatus", description = "Status (PENDING, QUEUED, RUNNING, SUCCEEDED, FAILED) and progress in "
            + "percent of a simulation run.")
    public Object getRunStatus(@ToolParam(description = "Simulation run id") long runId, ToolContext context) {
        return guarded(context, () -> status(simulations.view(runId), context));
    }

    @Tool(name = "waitForRun", description = "Waits until a simulation run finishes or the wait time is over, then "
            + "returns its status. Use for runs that were just started; if it is still running afterwards, tell the "
            + "user the run id and that the results can be asked for later.")
    public Object waitForRun(@ToolParam(description = "Simulation run id") long runId,
                             @ToolParam(description = "Maximum seconds to wait (1-60)", required = false)
                             Integer maxWaitSeconds, ToolContext context) {
        return guarded(context, () -> {
            int limit = Math.min(properties.maxWaitSeconds(), maxWaitSeconds == null ? 45 : Math.max(1, maxWaitSeconds));
            long deadline = System.nanoTime() + limit * 1_000_000_000L;
            SimulationRunView run = simulations.view(runId);
            while (!FINISHED.contains(run.status()) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                run = simulations.view(runId);
            }
            return status(run, context);
        });
    }

    @Tool(name = "getRunResults", description = "Portfolio results of a finished run: default and churn rates "
            + "(mean, P5, P50, P95; rates as fractions, 0.0323 = 3.23 %), expected loss and P99 loss in TRY, EL as % "
            + "of EAD, the delta against the baseline, the macro path and the cumulative default rate per quarter.")
    public Object getRunResults(@ToolParam(description = "Simulation run id") long runId, ToolContext context) {
        return guarded(context, () -> {
            SimulationRunView run = simulations.view(runId);
            TurnContext.of(context).addRunId(runId);
            if (!"SUCCEEDED".equals(run.status())) {
                return status(run, context);
            }
            return RunSummaries.results(json, run);
        });
    }

    @Tool(name = "compareRuns", description = "Compares finished runs side by side (default rate, churn, expected and "
            + "P99 loss, segment default rates) with differences against the first run.")
    public Object compareRuns(@ToolParam(description = "Run ids, the reference first") List<Long> runIds,
                              ToolContext context) {
        return guarded(context, () -> {
            RunComparison comparison = simulations.compare(runIds);
            runIds.forEach(TurnContext.of(context)::addRunId);
            return comparison;
        });
    }

    @Tool(name = "getSegmentImpact", description = "Per customer segment (MIN_WAGE, RETIREE, SELF_EMPLOYED, STUDENT, "
            + "WHITE_COLLAR): default and churn rates, expected and P99 loss, and the change against the baseline.")
    public Object getSegmentImpact(@ToolParam(description = "Simulation run id") long runId, ToolContext context) {
        return guarded(context, () -> {
            SimulationRunView run = simulations.view(runId);
            TurnContext.of(context).addRunId(runId);
            return "SUCCEEDED".equals(run.status()) ? RunSummaries.segments(json, run) : status(run, context);
        });
    }

    @Tool(name = "getTopAffectedCustomers", description = "Customers whose PD rises most in the run, with start and "
            + "horizon PD and the features driving the increase (Turkish texts).")
    public Object getTopAffectedCustomers(@ToolParam(description = "Simulation run id") long runId,
                                          @ToolParam(description = "How many (1-20, default 5)", required = false)
                                          Integer limit, ToolContext context) {
        return guarded(context, () -> {
            SimulationRunView run = simulations.view(runId);
            TurnContext.of(context).addRunId(runId);
            if (!"SUCCEEDED".equals(run.status())) {
                return status(run, context);
            }
            int n = limit == null ? 5 : Math.max(1, Math.min(limit, 20));
            ArrayNode top = json.createArrayNode();
            for (JsonNode c : run.result().path("topCustomers")) {
                if (top.size() == n) {
                    break;
                }
                ObjectNode row = top.addObject();
                row.set("customerId", c.path("customerId"));
                row.set("segment", c.path("segment"));
                row.set("pdStart", c.path("pdStart"));
                row.set("pdHorizon", c.path("pdHorizon"));
                row.set("defaultProbability", c.path("defaultProbability"));
                ArrayNode reasons = row.putArray("reasons");
                c.path("reasons").forEach(r -> reasons.add(r.path("textTr").asText()));
            }
            return Map.of("runId", runId, "customers", top);
        });
    }

    @Tool(name = "getPortfolioRiskSummary", description = "Current portfolio risk from the latest twin scoring run: "
            + "customers, mean 6-month PD and churn probability (fractions), customers per risk grade 1-10 and "
            + "segment averages.")
    public Object getPortfolioRiskSummary(ToolContext context) {
        return guarded(context, () -> risk.portfolioSummary().<Object>map(s -> s).orElse(Map.of("error",
                "NO_SCORES", "message", "The portfolio has not been scored yet (POST /api/admin/risk/score-portfolio).")));
    }

    private Map<String, Object> status(SimulationRunView run, ToolContext context) {
        TurnContext.of(context).addRunId(run.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.id());
        out.put("scenarioCode", run.scenarioCode());
        out.put("status", run.status());
        out.put("progressPct", run.progress());
        if (run.error() != null) {
            out.put("error", run.error());
        }
        if (run.durationSeconds() != null) {
            out.put("durationSeconds", run.durationSeconds());
        }
        return out;
    }

    private Map<String, Object> scenario(ScenarioView s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenarioId", s.id());
        out.put("code", s.code());
        out.put("name", s.name());
        out.put("description", s.description());
        out.put("preset", s.preset());
        out.put("parameters", s.parameters());
        return out;
    }

    /** Runs a tool body for a risk analyst; domain errors become error objects the model can explain. */
    private Object guarded(ToolContext context, Supplier<Object> body) {
        if (!TurnContext.of(context).user().has(Role.RISK_ANALYST)) {
            return Map.of("error", "FORBIDDEN", "message", "Only risk analysts can use the scenario tools.");
        }
        try {
            return body.get();
        } catch (SimulationNotFoundException e) {
            return Map.of("error", "NOT_FOUND", "message", e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException | DateTimeParseException e) {
            return Map.of("error", "REJECTED", "message", String.valueOf(e.getMessage()));
        } catch (MlServiceUnavailableException e) {
            return Map.of("error", "ML_SERVICE_UNAVAILABLE", "message", e.getMessage());
        }
    }
}
