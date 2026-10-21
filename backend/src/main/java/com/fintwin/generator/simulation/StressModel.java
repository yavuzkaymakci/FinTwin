package com.fintwin.generator.simulation;

/**
 * Structural model of a customer's hidden financial stress and the event probabilities derived from it.
 * <p>
 * Each month-end:
 * <pre>
 *   x_t      = b0 + b1*min(DTI, 2) + b2*clip(realIncomeLoss) + b3*min(utilisation, 1.2) + b4*min(volatility, 1.5)
 *              - b5*resilience - b6*ln(1 + bufferMonths) + b7*min(dpd/30, 3) + noise
 *   stress_t = rho * stress_{t-1} + (1 - rho) * sigmoid(x_t)
 * </pre>
 * Stress then drives behaviour in the next month (missed payments, minimum-only card payments, spending cuts,
 * distressed borrowing and churn), which feeds back into DTI, utilisation and arrears.
 */
public final class StressModel {

    /** Observable drivers of stress for one month. */
    public record Drivers(double debtToIncome, double realIncomeLoss, double cardUtilization,
                          double incomeVolatility, double resilience, double bufferMonths, int daysPastDue) {
    }

    static final double B0 = -2.4;
    static final double B_DTI = 2.0;
    static final double B_REAL_INCOME_LOSS = 3.0;
    static final double B_UTILIZATION = 1.8;
    static final double B_VOLATILITY = 1.5;
    static final double B_RESILIENCE = 0.6;
    static final double B_BUFFER = 0.5;
    static final double B_ARREARS = 0.8;
    static final double PERSISTENCE = 0.6;

    static final double MISS_INTERCEPT = -7.5;
    static final double MISS_SLOPE = 7.0;

    static final double CURE_INTERCEPT = 4.0;
    static final double CURE_SLOPE = -6.5;

    static final double CHURN_INTERCEPT = -6.1;
    static final double CHURN_STRESS_SLOPE = 4.0;
    static final double CHURN_NEW_CUSTOMER = 0.6;

    private StressModel() {
    }

    public static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /** Stress logit without noise. */
    public static double logit(Drivers d) {
        return B0
                + B_DTI * Math.min(d.debtToIncome(), 2.0)
                + B_REAL_INCOME_LOSS * Math.clamp(d.realIncomeLoss(), -0.3, 0.8)
                + B_UTILIZATION * Math.min(d.cardUtilization(), 1.2)
                + B_VOLATILITY * Math.min(d.incomeVolatility(), 1.5)
                - B_RESILIENCE * d.resilience()
                - B_BUFFER * Math.log1p(Math.max(d.bufferMonths(), 0))
                + B_ARREARS * Math.min(d.daysPastDue() / 30.0, 3.0);
    }

    public static double nextStress(double previousStress, Drivers drivers, double noise) {
        return PERSISTENCE * previousStress + (1 - PERSISTENCE) * sigmoid(logit(drivers) + noise);
    }

    /** Probability of a behavioural missed payment (forgetting, prioritising other debts) at a due date. */
    public static double missProbability(double stress) {
        return sigmoid(MISS_INTERCEPT + MISS_SLOPE * stress);
    }

    /**
     * Probability that a delinquent customer makes the effort to clear arrears at a payment opportunity (salary
     * day, due date). Falls steeply with stress, which is what turns early delinquency into default.
     */
    public static double cureProbability(double stress) {
        return sigmoid(CURE_INTERCEPT + CURE_SLOPE * stress);
    }

    /**
     * Monthly probability of deciding to leave the bank.
     *
     * @param tenureMonths months since becoming a customer
     */
    public static double churnHazard(double stress, double churnPropensity, double segmentOffset, int tenureMonths) {
        double logit = CHURN_INTERCEPT + CHURN_STRESS_SLOPE * stress + churnPropensity + segmentOffset
                + (tenureMonths < 12 ? CHURN_NEW_CUSTOMER : 0);
        return sigmoid(logit);
    }
}
