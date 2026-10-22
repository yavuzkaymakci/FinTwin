package com.fintwin.assistant.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintwin.assistant.core.TurnContext;
import com.fintwin.assistant.user.CurrentUser;
import com.fintwin.assistant.user.Role;
import com.fintwin.ml.MlGoalApi;
import com.fintwin.ml.MlServiceClient;
import com.fintwin.ml.MlServiceUnavailableException;
import com.fintwin.simulation.dto.CustomerForecastView;
import com.fintwin.simulation.service.SimulationNotFoundException;
import com.fintwin.simulation.service.SimulationService;
import com.fintwin.twin.dto.TwinView;
import com.fintwin.twin.projection.TwinFeatures;
import com.fintwin.twin.service.TwinNotFoundException;
import com.fintwin.twin.service.TwinQueryService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Tools of the customer coach. None of them takes a customer id: the customer is always the authenticated caller,
 * read from the server-side {@link TurnContext} ({@link CurrentUser#customerId()}). A model that is tricked into
 * asking for someone else's data has no parameter to put the other id into, and extra arguments are ignored.
 */
@Component
public class CoachTools {

    private final TwinQueryService twins;
    private final SimulationService simulations;
    private final MlServiceClient ml;

    public CoachTools(TwinQueryService twins, SimulationService simulations, MlServiceClient ml) {
        this.twins = twins;
        this.simulations = simulations;
        this.ml = ml;
    }

    /** A spending-cut option for the goal simulation. */
    public record CategoryCut(
            @ToolParam(description = "Category: RESTAURANT, ENTERTAINMENT, TRAVEL, CLOTHING, ELECTRONICS, ECOMMERCE, "
                    + "GROCERY, UTILITIES, TELECOM, RENT, HEALTH, TRANSPORT, FUEL, EDUCATION or CASH_WITHDRAWAL")
            String category,
            @ToolParam(description = "Reduction in percent, 0-100 (50 = half)") double cutPct) {
    }

    @Tool(name = "getMyTwinSummary", description = "The signed-in customer's financial twin: balances (TRY checking, "
            + "time deposits, USD), card debt and limit, loans, estimated monthly income and expenses, liquidity "
            + "buffer in months and late payments. Amounts in TRY unless noted.")
    public Object getMyTwinSummary(ToolContext context) {
        return forCustomer(context, customerId -> {
            TwinView twin = twins.current(customerId);
            TwinFeatures f = twin.features();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("segment", twin.segment());
            out.put("asOf", twin.asOf());
            out.put("checkingBalance", f.checkingBalance());
            out.put("depositBalance", f.depositBalance());
            out.put("usdBalance", f.usdBalance());
            out.put("cardDebt", f.cardDebt());
            out.put("cardLimit", f.cardLimit());
            out.put("cardUtilizationPct", pct(f.utilizationRate()));
            out.put("activeLoans", f.activeLoans());
            out.put("loanOutstanding", f.loanOutstanding());
            out.put("monthlyObligations", f.monthlyObligations());
            out.put("estimatedMonthlyIncome", f.incomeEstimate());
            out.put("averageMonthlyExpense", f.avgMonthlyExpense());
            out.put("liquidityBufferMonths", round(f.liquidityBufferMonths(), 1));
            out.put("latePaymentCount", f.latePaymentCount());
            return out;
        });
    }

    @Tool(name = "getMySpendingBreakdown", description = "The signed-in customer's spending of the last 3 months "
            + "(90 days) per category: total, monthly average and share in percent, plus the last 30 days.")
    public Object getMySpendingBreakdown(ToolContext context) {
        return forCustomer(context, customerId -> {
            TwinFeatures f = twins.current(customerId).features();
            BigDecimal total = f.spend90d() == null ? BigDecimal.ZERO : f.spend90d();
            List<Map<String, Object>> categories = new ArrayList<>();
            if (f.spendByCategory90d() != null) {
                f.spendByCategory90d().entrySet().stream()
                        .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                        .forEach(e -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("category", e.getKey());
                            row.put("last3Months", e.getValue());
                            row.put("monthlyAverage", e.getValue().divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP));
                            row.put("sharePct", total.signum() == 0 ? null : e.getValue().multiply(BigDecimal.valueOf(100))
                                    .divide(total, 1, RoundingMode.HALF_UP));
                            categories.add(row);
                        });
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("last3MonthsTotal", total);
            out.put("monthlyAverage", total.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP));
            out.put("last30Days", f.spend30d());
            out.put("categories", categories);
            return out;
        });
    }

    @Tool(name = "getMyCashFlowForecast", description = "The signed-in customer's cash-flow forecast from the latest "
            + "portfolio simulation: per month the P10 / P50 / P90 band of the liquid balance (TRY incl. USD) and of "
            + "the liquidity buffer in months, and the scenario it is based on.")
    public Object getMyCashFlowForecast(ToolContext context) {
        return forCustomer(context, customerId -> {
            CustomerForecastView view = simulations.forecast(customerId, null);
            JsonNode f = view.forecast();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scenario", view.scenarioName());
            out.put("runId", view.runId());
            out.put("startBalance", f.path("startBalance").asDouble());
            out.put("startLiquidityBufferMonths", f.path("startLiquidityBufferMonths").asDouble());
            List<JsonNode> months = new ArrayList<>();
            JsonNode all = f.path("months");
            for (int i = 0; i < all.size(); i++) {
                if (i % 3 == 2 || i == all.size() - 1) {
                    months.add(all.get(i));
                }
            }
            out.put("months", months);
            return out;
        });
    }

    @Tool(name = "simulateSavingsGoal", description = "Simulates whether the signed-in customer reaches a savings "
            + "target (nominal TRY) within a number of months from their current balances, with optional extra "
            + "monthly saving and spending cuts per category. Returns the probability of reaching the target with "
            + "and without the changes, the extra monthly saving needed for 80 % probability, the monthly saving the "
            + "cuts free up and the balance band at the horizon.")
    public Object simulateSavingsGoal(
            @ToolParam(description = "Savings target in TRY, e.g. 500000") double targetAmount,
            @ToolParam(description = "Months to reach it, 1-120 (3 years = 36)") int months,
            @ToolParam(description = "Extra amount saved every month in TRY", required = false) Double extraMonthlySaving,
            @ToolParam(description = "Spending cuts per category", required = false) List<CategoryCut> categoryCuts,
            ToolContext context) {
        return forCustomer(context, customerId -> {
            List<MlGoalApi.CategoryCut> cuts = categoryCuts == null ? List.of() : categoryCuts.stream()
                    .map(c -> new MlGoalApi.CategoryCut(c.category(), c.cutPct())).toList();
            JsonNode r = ml.simulateGoal(new MlGoalApi.GoalRequest(customerId, targetAmount, months,
                    extraMonthlySaving, cuts, null, null));
            Map<String, Object> out = new LinkedHashMap<>();
            for (String field : new String[]{"targetAmount", "months", "horizonMonth", "startBalance",
                    "probabilityWithoutChanges", "probabilityWithChanges", "extraMonthlySaving", "categoryCuts",
                    "monthlySavingFromCuts", "confidence", "requiredExtraMonthlySaving",
                    "balanceAtHorizonWithoutChanges", "balanceAtHorizonWithChanges", "assumptions"}) {
                if (!r.path(field).isMissingNode()) {
                    out.put(field, r.path(field));
                }
            }
            return out;
        });
    }

    /** Runs a tool body for the signed-in customer only; domain errors become error objects. */
    private Object forCustomer(ToolContext context, Function<Long, Object> body) {
        CurrentUser user = TurnContext.of(context).user();
        if (!user.has(Role.CUSTOMER) || user.customerId() == null) {
            return Map.of("error", "FORBIDDEN", "message", "Only a signed-in customer can use the coach tools.");
        }
        try {
            return body.apply(user.customerId());
        } catch (TwinNotFoundException | SimulationNotFoundException e) {
            return Map.of("error", "NOT_FOUND", "message", "No data available yet for this customer.");
        } catch (HttpClientErrorException e) {
            return Map.of("error", "REJECTED", "message", e.getResponseBodyAsString());
        } catch (MlServiceUnavailableException e) {
            return Map.of("error", "ML_SERVICE_UNAVAILABLE", "message", "The simulation service is not available.");
        }
    }

    private static Double pct(Double fraction) {
        return fraction == null ? null : Math.round(fraction * 1000) / 10.0;
    }

    private static Double round(Double value, int digits) {
        if (value == null) {
            return null;
        }
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}
