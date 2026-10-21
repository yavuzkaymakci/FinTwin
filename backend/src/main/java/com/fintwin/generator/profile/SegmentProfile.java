package com.fintwin.generator.profile;

import com.fintwin.customer.domain.OccupationGroup;
import com.fintwin.customer.domain.Segment;
import com.fintwin.generator.catalog.Category;
import com.fintwin.generator.rng.Rand;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Behavioural parameters of a customer segment. Money amounts are monthly TRY at the price level of the base month
 * (the first simulated month); the simulation indexes them with inflation.
 *
 * @param weight               share of the portfolio
 * @param incomeMin            lower bound of base monthly net income
 * @param incomeMax            upper bound of base monthly net income
 * @param incomeMedian         median of the (clamped) lognormal income distribution
 * @param incomeSigma          lognormal sigma of income
 * @param spendRatio           share of income consumed (rent, bills and discretionary spending)
 * @param cardSpendShare       share of card-eligible spending paid by credit card when the customer has one
 * @param payerWeights         weights of TRANSACTOR, REVOLVER, MIN_PAYER card repayment styles
 * @param monthlyJobLossRate   monthly hazard of losing the main income (business collapse for self-employed)
 * @param churnLogitOffset     segment effect on the churn hazard logit
 * @param cashShare            share of discretionary budget withdrawn as cash
 */
public record SegmentProfile(
        Segment segment,
        double weight,
        int ageMin,
        int ageMax,
        double incomeMin,
        double incomeMax,
        double incomeMedian,
        double incomeSigma,
        Map<OccupationGroup, Double> occupations,
        double cardProbability,
        double personalLoanProbability,
        double mortgageProbability,
        double timeDepositProbability,
        double usdAccountProbability,
        double renterProbability,
        double carProbability,
        double spendRatio,
        double cardSpendShare,
        double[] payerWeights,
        double monthlyJobLossRate,
        double churnLogitOffset,
        double cashShare,
        double onlineAffinity,
        Map<Category, Double> countMultipliers) {

    /** Net monthly minimum wage in TRY by year (2024–2026 official figures). */
    public static double minimumWage(int year) {
        if (year <= 2024) {
            return 17_002.12;
        }
        if (year == 2025) {
            return 22_104.67;
        }
        // 2026 figure; later years are indexed by the simulation via the generic raise logic.
        return 28_075.50;
    }

    private static final Map<Segment, SegmentProfile> PROFILES = new EnumMap<>(Segment.class);

    static {
        PROFILES.put(Segment.STUDENT, new SegmentProfile(Segment.STUDENT, 0.12, 18, 26,
                3_500, 16_000, 7_500, 0.35,
                Map.of(OccupationGroup.STUDENT, 1.0),
                0.35, 0.03, 0.0, 0.05, 0.05, 0.35, 0.05,
                0.92, 0.50, new double[]{0.35, 0.35, 0.30},
                0.0, 0.3, 0.08, 1.3,
                Map.of(Category.RESTAURANT, 1.3, Category.ENTERTAINMENT, 1.4, Category.ECOMMERCE, 1.2,
                        Category.GROCERY, 0.6, Category.TRANSPORT, 1.4, Category.TRAVEL, 0.6)));
        PROFILES.put(Segment.MIN_WAGE, new SegmentProfile(Segment.MIN_WAGE, 0.28, 20, 60,
                17_002, 23_800, 18_500, 0.10,
                Map.of(OccupationGroup.MANUFACTURING, 0.30, OccupationGroup.RETAIL_SERVICES, 0.30,
                        OccupationGroup.HOSPITALITY, 0.15, OccupationGroup.CONSTRUCTION, 0.10,
                        OccupationGroup.LOGISTICS, 0.15),
                0.65, 0.30, 0.02, 0.08, 0.05, 0.55, 0.25,
                0.93, 0.55, new double[]{0.25, 0.40, 0.35},
                0.0070, 0.0, 0.12, 0.8,
                Map.of(Category.RESTAURANT, 0.7, Category.ECOMMERCE, 0.8, Category.TRAVEL, 0.4)));
        PROFILES.put(Segment.WHITE_COLLAR, new SegmentProfile(Segment.WHITE_COLLAR, 0.32, 23, 62,
                28_000, 250_000, 52_000, 0.45,
                Map.of(OccupationGroup.OFFICE_WORKER, 0.30, OccupationGroup.IT_PROFESSIONAL, 0.15,
                        OccupationGroup.FINANCE, 0.10, OccupationGroup.HEALTHCARE, 0.10,
                        OccupationGroup.EDUCATION, 0.10, OccupationGroup.PUBLIC_SECTOR, 0.25),
                0.92, 0.32, 0.10, 0.40, 0.30, 0.45, 0.60,
                0.80, 0.75, new double[]{0.55, 0.30, 0.15},
                0.0035, 0.0, 0.04, 1.3,
                Map.of(Category.RESTAURANT, 1.3, Category.ECOMMERCE, 1.5, Category.TRAVEL, 1.6)));
        PROFILES.put(Segment.RETIREE, new SegmentProfile(Segment.RETIREE, 0.16, 50, 85,
                12_500, 45_000, 19_000, 0.30,
                Map.of(OccupationGroup.RETIRED, 1.0),
                0.55, 0.22, 0.02, 0.35, 0.12, 0.20, 0.35,
                0.86, 0.45, new double[]{0.55, 0.25, 0.20},
                0.0, -0.5, 0.15, 0.4,
                Map.of(Category.GROCERY, 1.2, Category.HEALTH, 2.0, Category.RESTAURANT, 0.5,
                        Category.ECOMMERCE, 0.4, Category.ENTERTAINMENT, 0.4, Category.TRANSPORT, 0.6)));
        PROFILES.put(Segment.SELF_EMPLOYED, new SegmentProfile(Segment.SELF_EMPLOYED, 0.12, 25, 68,
                15_000, 450_000, 48_000, 0.65,
                Map.of(OccupationGroup.SHOP_OWNER, 0.35, OccupationGroup.TRADESPERSON, 0.25,
                        OccupationGroup.FREELANCER, 0.25, OccupationGroup.CONSTRUCTION, 0.15),
                0.82, 0.30, 0.07, 0.30, 0.35, 0.35, 0.60,
                0.82, 0.65, new double[]{0.40, 0.35, 0.25},
                0.0035, 0.2, 0.08, 1.0,
                Map.of(Category.FUEL, 1.4, Category.RESTAURANT, 1.2)));
    }

    public static SegmentProfile of(Segment segment) {
        return PROFILES.get(segment);
    }

    public static List<SegmentProfile> all() {
        return List.copyOf(PROFILES.values());
    }

    public static Segment sampleSegment(Rand r) {
        List<SegmentProfile> all = all();
        double[] weights = all.stream().mapToDouble(SegmentProfile::weight).toArray();
        return all.get(r.pickWeighted(weights)).segment();
    }

    /** Base monthly net income, always within [incomeMin, incomeMax]. */
    public double sampleBaseIncome(Rand r, double cityIncomeFactor) {
        double income = segment == Segment.MIN_WAGE
                ? minimumWage(2024) * r.uniform(1.0, 1.4)
                : r.lognormalMedian(incomeMedian * cityIncomeFactor, incomeSigma);
        return Math.clamp(income, incomeMin, incomeMax);
    }

    public int sampleAge(Rand r) {
        return r.intBetween(ageMin, ageMax);
    }

    public OccupationGroup sampleOccupation(Rand r) {
        List<Map.Entry<OccupationGroup, Double>> entries = occupations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        double[] weights = entries.stream().mapToDouble(Map.Entry::getValue).toArray();
        return entries.get(r.pickWeighted(weights)).getKey();
    }

    public double countMultiplier(Category category) {
        return countMultipliers.getOrDefault(category, 1.0);
    }
}
