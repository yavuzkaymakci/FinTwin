package com.fintwin.assistant.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fintwin.simulation.dto.SimulationRunView;

/** Compact views of a run's stored result for the model (the full result is ~100 KB). */
final class RunSummaries {

    private RunSummaries() {
    }

    static ObjectNode results(ObjectMapper json, SimulationRunView run) {
        JsonNode r = run.result();
        JsonNode p = r.path("portfolio");
        ObjectNode out = json.createObjectNode();
        out.put("runId", run.id());
        out.put("scenarioCode", run.scenarioCode());
        out.put("scenarioName", run.scenarioName());
        out.set("startDate", r.path("startDate"));
        out.set("horizonMonths", r.path("horizonMonths"));
        out.set("paths", r.path("paths"));
        out.set("scenarioParameters", r.path("scenario"));
        ObjectNode portfolio = out.putObject("portfolio");
        for (String field : new String[]{"customers", "creditCustomers", "eadStart", "meanPdStart", "meanChurnStart",
                "defaultRate", "churnRate", "expectedLoss", "lossP99", "unexpectedLossP99", "expectedLossPctOfEad"}) {
            portfolio.set(field, p.path(field));
        }
        if (!r.path("deltaVsBaseline").isMissingNode() && !r.path("deltaVsBaseline").isNull()) {
            ObjectNode delta = out.putObject("deltaVsBaseline");
            delta.set("baselineScenarioCode", r.path("baseline").path("scenario").path("code"));
            delta.set("defaultRate", r.path("deltaVsBaseline").path("defaultRate"));
            delta.set("churnRate", r.path("deltaVsBaseline").path("churnRate"));
            delta.set("loss", r.path("deltaVsBaseline").path("loss"));
            delta.set("baselineDefaultRate", r.path("baseline").path("portfolio").path("defaultRate"));
            delta.set("baselineExpectedLoss", r.path("baseline").path("portfolio").path("expectedLoss"));
        }
        ArrayNode quarters = out.putArray("cumulativeDefaultRateByQuarter");
        JsonNode monthly = p.path("monthly");
        for (int i = 2; i < monthly.size(); i += 3) {
            ObjectNode q = quarters.addObject();
            q.set("month", monthly.get(i).path("month"));
            q.set("mean", monthly.get(i).path("cumulativeDefaultRate").path("mean"));
        }
        ArrayNode macro = out.putArray("macroPathEveryQuarter");
        JsonNode path = r.path("macroPath");
        for (int i = 2; i < path.size(); i += 3) {
            ObjectNode m = macro.addObject();
            m.set("month", path.get(i).path("month"));
            m.set("inflationPct", path.get(i).path("inflationPct"));
            m.set("usdTry", path.get(i).path("usdTry"));
            m.set("policyRatePct", path.get(i).path("policyRatePct"));
        }
        out.put("note", "Rates are fractions of credit customers (default) or all customers (churn) over the horizon; "
                + "losses in TRY; product-level (card vs loan) results are not available.");
        return out;
    }

    static ObjectNode segments(ObjectMapper json, SimulationRunView run) {
        JsonNode r = run.result();
        ObjectNode out = json.createObjectNode();
        out.put("runId", run.id());
        out.put("scenarioCode", run.scenarioCode());
        ArrayNode rows = out.putArray("segments");
        JsonNode deltas = r.path("deltaVsBaseline").path("segments");
        for (JsonNode s : r.path("segments")) {
            ObjectNode row = rows.addObject();
            row.set("segment", s.path("segment"));
            row.set("customers", s.path("customers"));
            row.set("creditCustomers", s.path("creditCustomers"));
            row.set("meanPdStart", s.path("meanPdStart"));
            row.set("defaultRate", s.path("defaultRate"));
            row.set("churnRate", s.path("churnRate").path("mean"));
            row.set("expectedLoss", s.path("expectedLoss"));
            row.set("lossP99", s.path("lossP99"));
            for (JsonNode d : deltas) {
                if (d.path("segment").equals(s.path("segment"))) {
                    row.set("defaultRateDeltaVsBaseline", d.path("defaultRate").path("mean"));
                    row.set("expectedLossDeltaVsBaseline", d.path("expectedLoss"));
                }
            }
        }
        return out;
    }
}
