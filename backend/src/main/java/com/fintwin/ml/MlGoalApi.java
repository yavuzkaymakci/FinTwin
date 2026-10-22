package com.fintwin.ml;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Wire format of the ML service's savings-goal endpoint (see ml-service app/simulation/goals.py). */
public final class MlGoalApi {

    private MlGoalApi() {
    }

    public record CategoryCut(String category, double cutPct) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GoalRequest(long customerId, double targetAmount, int months, Double extraMonthlySaving,
                              List<CategoryCut> categoryCuts, Double confidence, Integer paths) {
    }
}
