package com.fintwin.generator.simulation;

import com.fintwin.account.domain.AccountType;
import com.fintwin.account.domain.Currency;
import com.fintwin.customer.domain.CustomerEventType;
import com.fintwin.customer.domain.CustomerStatus;
import com.fintwin.customer.domain.OccupationGroup;
import com.fintwin.customer.domain.Segment;
import com.fintwin.generator.calendar.TurkishCalendar;
import com.fintwin.generator.catalog.Category;
import com.fintwin.generator.catalog.City;
import com.fintwin.generator.catalog.MerchantCatalog;
import com.fintwin.generator.catalog.MerchantCatalog.MerchantRef;
import com.fintwin.generator.catalog.Names;
import com.fintwin.generator.installment.InstallmentScheduler;
import com.fintwin.generator.installment.InstallmentScheduler.ScheduledInstallment;
import com.fintwin.generator.macro.MacroPath;
import com.fintwin.generator.profile.SegmentProfile;
import com.fintwin.generator.rng.Rand;
import com.fintwin.generator.rng.SeededRandoms;
import com.fintwin.generator.simulation.Sim.CustomerHistory;
import com.fintwin.generator.simulation.Sim.Installment;
import com.fintwin.generator.simulation.Sim.PayerType;
import com.fintwin.generator.simulation.Sim.Statement;
import com.fintwin.generator.text.DescriptionNoiser;
import com.fintwin.loan.domain.LoanStatus;
import com.fintwin.loan.domain.LoanType;
import com.fintwin.transaction.domain.Channel;
import com.fintwin.transaction.domain.Direction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates one customer day by day over the horizon: income, fixed costs, discretionary spending, card cycles,
 * loans, savings, and the hidden financial stress that drives late payments, defaults and churn.
 * <p>
 * Pure and deterministic: the output depends only on (seed, customer index, macro path, merchant catalog,
 * horizon). Instances are thread-safe; each {@link #simulate(int)} call uses its own state.
 */
public final class CustomerSimulator {

    /** Turkey has used UTC+3 all year since 2016. */
    static final ZoneOffset ISTANBUL = ZoneOffset.ofHours(3);

    /** Profile amounts (incomes, rents, bills) are expressed at this month's price level. */
    public static final YearMonth REFERENCE_MONTH = YearMonth.of(2024, 10);

    private record CategorySpec(double monthlyCount, double budgetShare, double ticketSigma, double onlineShare,
                                int fromHour, int toHour) {
    }

    private static final Map<Category, CategorySpec> SPECS = new EnumMap<>(Category.class);

    static {
        SPECS.put(Category.GROCERY, new CategorySpec(12, 0.30, 0.6, 0.08, 9, 22));
        SPECS.put(Category.RESTAURANT, new CategorySpec(7, 0.12, 0.5, 0.25, 11, 23));
        SPECS.put(Category.FUEL, new CategorySpec(3.5, 0.10, 0.3, 0.0, 7, 22));
        SPECS.put(Category.TRANSPORT, new CategorySpec(6, 0.04, 0.4, 0.15, 7, 21));
        SPECS.put(Category.CLOTHING, new CategorySpec(1.5, 0.08, 0.6, 0.35, 11, 21));
        SPECS.put(Category.ELECTRONICS, new CategorySpec(0.4, 0.06, 0.8, 0.5, 11, 21));
        SPECS.put(Category.ECOMMERCE, new CategorySpec(3, 0.10, 0.8, 1.0, 9, 24));
        SPECS.put(Category.HEALTH, new CategorySpec(1.2, 0.04, 0.8, 0.0, 9, 19));
        SPECS.put(Category.ENTERTAINMENT, new CategorySpec(2, 0.04, 0.5, 0.5, 13, 24));
        SPECS.put(Category.EDUCATION, new CategorySpec(0.6, 0.04, 0.7, 0.3, 9, 18));
        SPECS.put(Category.TRAVEL, new CategorySpec(0.4, 0.06, 0.7, 0.75, 8, 23));
    }

    /** Base probability that an eligible card purchase is split into installments. */
    private static final Map<Category, Double> INSTALLMENT_PROPENSITY = Map.of(
            Category.ELECTRONICS, 0.60, Category.TRAVEL, 0.50, Category.EDUCATION, 0.40,
            Category.CLOTHING, 0.30, Category.HEALTH, 0.30, Category.ECOMMERCE, 0.25);
    private static final double INSTALLMENT_MIN_AMOUNT = 750;
    private static final int[] INSTALLMENT_OPTIONS = {2, 3, 4, 6, 9, 12};
    private static final double[] INSTALLMENT_WEIGHTS = {0.10, 0.30, 0.05, 0.30, 0.15, 0.10};
    private static final List<Category> BIG_TICKET_CATEGORIES = List.of(Category.ELECTRONICS, Category.TRAVEL,
            Category.CLOTHING, Category.HEALTH, Category.EDUCATION);

    private static final String[] TRANSFER_PURPOSES = {"", "BORC", "HEDIYE", "AIDAT", "PAYLASIM", "ODEME"};
    private static final double NEW_CUSTOMER_SHARE = 0.15;
    private static final double MIN_PAYMENT_LIMIT_THRESHOLD = 50_000;
    private static final int CARD_DUE_DAYS = 10;
    private static final int DEFAULT_DAYS_PAST_DUE = 90;

    private final MacroPath macro;
    private final MerchantCatalog catalog;
    private final YearMonth startMonth;
    private final int months;
    private final long seed;
    private final double referenceScale;

    public CustomerSimulator(MacroPath macro, MerchantCatalog catalog, YearMonth startMonth, int months, long seed) {
        if (!macro.baseMonth().equals(startMonth)) {
            throw new IllegalArgumentException("macro path must be based on the start month");
        }
        this.macro = macro;
        this.catalog = catalog;
        this.startMonth = startMonth;
        this.months = months;
        this.seed = seed;
        this.referenceScale = 1.0 / macro.priceIndex(REFERENCE_MONTH);
    }

    public CustomerHistory simulate(int customerIndex) {
        return new Run(customerIndex).execute();
    }

    /** Monthly contractual card rate, tracking the policy rate within the regulatory band. */
    static double cardMonthlyRate(double annualPolicyRate) {
        return Math.clamp(annualPolicyRate / 12 * 0.95 + 0.0025, 0.0175, 0.05);
    }

    /** BDDK maturity caps for consumer loans by principal (TRY). */
    static int maxPersonalLoanTerm(double principal) {
        if (principal <= 50_000) {
            return 36;
        }
        return principal <= 100_000 ? 24 : 12;
    }

    private static final class CardPlan {
        final long perInstallment;
        long firstExtra;
        int remaining;

        CardPlan(long perInstallment, long firstExtra, int remaining) {
            this.perInstallment = perInstallment;
            this.firstExtra = firstExtra;
            this.remaining = remaining;
        }

        long unbilled() {
            return perInstallment * remaining + firstExtra;
        }
    }

    private static final class DayPlan {
        final List<Runnable> income = new ArrayList<>();
        final List<Runnable> fixed = new ArrayList<>();
        final List<Runnable> spending = new ArrayList<>();
        final List<Runnable> other = new ArrayList<>();
    }

    private record Bill(MerchantRef biller, Category category, double baseAmount, int day, String subscriberNo,
                        int seasonality) {
        static final int NONE = 0;
        static final int GAS = 1;
        static final int ELECTRICITY = 2;
    }

    private record Subscription(MerchantRef merchant, double baseAmount, int day) {
    }

    /** Mutable state of one customer's simulation. */
    private final class Run {

        private final Rand r;
        private final SegmentProfile profile;
        private final City city;
        private final CustomerHistory h;
        private final int joinMonthIndex;
        private final LocalDate joinDate;
        private final Map<Category, List<MerchantRef>> favorites = new EnumMap<>(Category.class);

        // Latent traits
        private double resilience;
        private double churnPropensity;
        private PayerType payerType;
        private double spendingStyle;
        private double incomeVolatilityBase;
        private double passThrough;
        private Integer salaryDay;
        private boolean hasCar;
        private boolean renter;
        private double baseIncome;
        private String employer;
        private boolean publicSector;
        private boolean receivesScholarship;
        private int selfEmployedLag;

        // Income
        private double regularIncome;
        private double minWageMultiplier;
        private int unemployedMonthsLeft;
        private int unemployedMonthsElapsed;
        private int unemploymentMonths;
        private boolean julyRaise;
        private final Deque<Long> incomeHistory = new ArrayDeque<>();

        // Accounts
        private Sim.Account checking;
        private Sim.Account card;
        private Sim.Account deposit;
        private Sim.Account usd;
        private String cardLast4;
        private boolean autoPayFromCard;

        // Card cycle
        private long revolving;
        private long cycle;
        private long pastDue;
        private LocalDate cardOldestUnpaid;
        private final List<CardPlan> cardPlans = new ArrayList<>();
        private Statement pendingStatement;
        private final List<Statement> delinquentStatements = new ArrayList<>();

        // Loans
        private final List<Sim.Loan> activeLoans = new ArrayList<>();
        private final Map<Sim.Loan, List<Installment>> loanArrears = new LinkedHashMap<>();

        // Fixed costs (reference prices unless noted)
        private double rent;
        private int rentDay;
        private Month rentAnniversary;
        private String landlord;
        private final List<Bill> bills = new ArrayList<>();
        private final List<Subscription> subscriptions = new ArrayList<>();
        private double discretionaryRealBase;
        private double habitReal;

        // Behavioural state
        private double stress;
        private double stressSum;
        private double maxStress;
        private int latePayments;
        private boolean active = true;
        private LocalDate defaultDate;
        private LocalDate churnDate;
        private boolean churnIntent;
        private int churnMonthIndex = -1;

        // Month bookkeeping
        private int monthIndex;
        private YearMonth month;
        private DayPlan[] dayPlans;
        private long monthIncome;
        private long monthDebtService;
        private int sequence;

        Run(int index) {
            this.r = SeededRandoms.forCustomer(seed, index);
            Segment segment = SegmentProfile.sampleSegment(r);
            this.profile = SegmentProfile.of(segment);
            this.city = City.ALL.get(r.pickWeighted(City.weights()));
            int age = profile.sampleAge(r);
            OccupationGroup occupation = profile.sampleOccupation(r);
            this.joinMonthIndex = months > 1 && r.chance(NEW_CUSTOMER_SHARE) ? r.intBetween(1, months - 1) : 0;
            LocalDate since;
            if (joinMonthIndex == 0) {
                double maxYears = Math.min(15, age - 18 + 0.3);
                long daysBack = Math.round(365 * r.uniform(0.3, Math.max(0.31, maxYears)));
                since = startMonth.atDay(1).minusDays(daysBack);
                this.joinDate = startMonth.atDay(1);
            } else {
                since = startMonth.plusMonths(joinMonthIndex).atDay(r.intBetween(1, 28));
                this.joinDate = since;
            }
            this.h = new CustomerHistory(index, segment, age, city.name(), occupation, since);
        }

        CustomerHistory execute() {
            initialiseTraits();
            initialiseProducts();
            initialiseStress();
            LocalDate last = startMonth.plusMonths(months - 1L).atEndOfMonth();
            for (LocalDate d = joinDate; !d.isAfter(last) && active; d = d.plusDays(1)) {
                if (d.getDayOfMonth() == 1 || d.equals(joinDate)) {
                    beginMonth(YearMonth.from(d));
                }
                processDay(d);
                if (active && d.equals(month.atEndOfMonth())) {
                    endMonth();
                }
            }
            finish(active ? last : (defaultDate != null ? defaultDate : churnDate));
            return h;
        }

        // ---------------------------------------------------------------- initialisation

        private void initialiseTraits() {
            Segment segment = profile.segment();
            resilience = r.normal();
            churnPropensity = r.normal(0, 0.6);
            payerType = PayerType.values()[r.pickWeighted(profile.payerWeights())];
            spendingStyle = r.lognormalMedian(1.0, 0.12);
            incomeVolatilityBase = switch (segment) {
                case SELF_EMPLOYED -> r.uniform(0.15, 0.45);
                case STUDENT -> r.uniform(0.05, 0.2);
                default -> r.uniform(0.0, 0.05);
            };
            passThrough = switch (segment) {
                case WHITE_COLLAR -> r.uniform(0.5, 1.0);
                case SELF_EMPLOYED -> r.uniform(0.8, 1.0);
                case STUDENT -> r.uniform(0.5, 1.0);
                default -> 1.0;
            };
            hasCar = r.chance(profile.carProbability());
            renter = r.chance(profile.renterProbability());
            publicSector = h.occupation == OccupationGroup.PUBLIC_SECTOR
                    || (h.occupation == OccupationGroup.EDUCATION || h.occupation == OccupationGroup.HEALTHCARE)
                    && r.chance(0.5);
            salaryDay = switch (segment) {
                case MIN_WAGE -> r.intBetween(1, 5);
                case WHITE_COLLAR -> publicSector ? 15 : new int[]{1, 10, 15, 28}[r.pickWeighted(
                        new double[]{0.35, 0.15, 0.25, 0.25})];
                case RETIREE -> r.chance(0.7) ? r.intBetween(17, 26) : r.intBetween(1, 6);
                case STUDENT -> r.intBetween(1, 28);
                case SELF_EMPLOYED -> null;
            };
            employer = switch (h.occupation) {
                case RETIRED -> null;
                case STUDENT -> r.chance(0.3) ? Names.company(r, false) : null;
                case IT_PROFESSIONAL, FINANCE -> Names.company(r, true);
                default -> publicSector ? Names.publicEmployer(r) : Names.company(r, false);
            };
            receivesScholarship = segment == Segment.STUDENT && r.chance(0.4);
            selfEmployedLag = r.intBetween(2, 3);
            julyRaise = r.chance(0.5);

            baseIncome = profile.sampleBaseIncome(r, city.incomeFactor()) * referenceScale;
            minWageMultiplier = baseIncome / SegmentProfile.minimumWage(startMonth.getYear());
            regularIncome = baseIncome * (joinMonthIndex == 0 ? 1.0 : macro.priceIndex(YearMonth.from(joinDate)));

            if (renter) {
                rent = segment == Segment.STUDENT
                        ? Math.clamp(baseIncome * r.uniform(0.25, 0.4), 2_500 * referenceScale, 10_000 * referenceScale)
                        : Math.clamp(baseIncome * r.uniform(0.22, 0.38) * city.rentFactor(),
                        5_000 * city.rentFactor() * referenceScale, 80_000 * referenceScale);
                rentDay = r.intBetween(1, 5);
                rentAnniversary = Month.of(r.intBetween(1, 12));
                landlord = Names.personName(r);
            }
            boolean ownHousehold = segment != Segment.STUDENT || r.chance(0.5);
            double household = segment == Segment.STUDENT ? 0.5 : 1.0;
            List<MerchantRef> utilities = catalog.utilities(city);
            if (ownHousehold) {
                bills.add(new Bill(utilities.get(0), Category.UTILITIES, r.uniform(450, 1_200) * household,
                        r.intBetween(8, 25), r.digits(9), Bill.ELECTRICITY));
                bills.add(new Bill(utilities.get(1), Category.UTILITIES, r.uniform(500, 1_300) * household,
                        r.intBetween(8, 25), r.digits(9), Bill.GAS));
                bills.add(new Bill(utilities.get(2), Category.UTILITIES, r.uniform(200, 550) * household,
                        r.intBetween(8, 25), r.digits(8), Bill.NONE));
            }
            List<MerchantRef> telecom = catalog.online(Category.TELECOM);
            bills.add(new Bill(telecom.get(r.intBetween(0, 1)), Category.TELECOM,
                    segment == Segment.STUDENT ? r.uniform(200, 400) : r.uniform(250, 650), r.intBetween(1, 28),
                    "5" + r.digits(9), Bill.NONE));
            if (r.chance(ownHousehold ? 0.8 : 0.3)) {
                bills.add(new Bill(telecom.get(r.intBetween(1, 2)), Category.TELECOM, r.uniform(350, 600),
                        r.intBetween(1, 28), r.digits(10), Bill.NONE));
            }
            if (r.chance(0.2)) {
                bills.add(new Bill(telecom.get(3), Category.TELECOM, r.uniform(200, 400), r.intBetween(1, 28),
                        r.digits(8), Bill.NONE));
            }
            int maxSubscriptions = switch (segment) {
                case STUDENT, SELF_EMPLOYED -> 2;
                case WHITE_COLLAR -> 3;
                default -> 1;
            };
            int subscriptionCount = r.intBetween(0, maxSubscriptions);
            List<MerchantRef> streaming = catalog.online(Category.ENTERTAINMENT);
            for (int i = 0; i < subscriptionCount; i++) {
                subscriptions.add(new Subscription(streaming.get(i % 2), r.uniform(90, 260), r.intBetween(1, 28)));
            }
        }

        private void initialiseProducts() {
            LocalDate since = h.customerSince;
            boolean existing = joinMonthIndex == 0;
            LocalDate horizonStart = startMonth.atDay(1);
            checking = openAccount(AccountType.CHECKING, Currency.TRY, since);
            checking.balanceCents = Sim.toCents(baseIncome * r.uniform(0.05, 0.8)
                    * (profile.segment() == Segment.RETIREE ? 1.5 : 1.0));

            double productScale = existing ? 1.0 : 0.6;
            if (r.chance(profile.cardProbability() * productScale)) {
                LocalDate opened = existing ? randomDateBetween(since, horizonStart.minusDays(1)) : since;
                double limit = baseIncome * r.uniform(1.5, 3.5);
                if (profile.segment() == Segment.STUDENT) {
                    limit = Math.min(limit, 15_000 * referenceScale);
                }
                limit = Math.max(3_000, Math.round(limit / 500) * 500.0);
                card = openAccount(AccountType.CREDIT_CARD, Currency.TRY, opened);
                card.creditLimitCents = Sim.toCents(limit);
                card.statementDay = r.intBetween(1, 28);
                card.dueDay = startMonth.atDay(card.statementDay).plusDays(CARD_DUE_DAYS).getDayOfMonth();
                cardLast4 = r.digits(4);
                autoPayFromCard = r.chance(0.35);
                if (existing) {
                    double used = switch (payerType) {
                        case TRANSACTOR -> 0.0;
                        case REVOLVER -> r.uniform(0.15, 0.45);
                        case MIN_PAYER -> r.uniform(0.3, 0.7);
                    };
                    revolving = Math.round(card.creditLimitCents * used);
                }
            }
            if (r.chance(profile.timeDepositProbability() * productScale)) {
                openDeposit(existing ? randomDateBetween(since, horizonStart.minusDays(1)) : since,
                        Sim.toCents(baseIncome * r.lognormalMedian(3, 0.8)));
            }
            if (r.chance(profile.usdAccountProbability() * productScale)) {
                usd = openAccount(AccountType.CHECKING, Currency.USD,
                        existing ? randomDateBetween(since, horizonStart.minusDays(1)) : since);
                usd.balanceCents = Sim.toCents(baseIncome * r.uniform(0.5, 4) / macro.usdTry(startMonth));
            }
            if (existing) {
                long tenureMonths = ChronoUnit.MONTHS.between(YearMonth.from(since), startMonth);
                if (r.chance(profile.personalLoanProbability()) && tenureMonths >= 2) {
                    createExistingLoan(LoanType.PERSONAL, (int) tenureMonths);
                }
                if (r.chance(profile.mortgageProbability()) && tenureMonths >= 2) {
                    createExistingLoan(LoanType.MORTGAGE, (int) tenureMonths);
                    renter = false;
                }
            }
            for (Sim.Account account : h.accounts) {
                account.openingBalanceCents = account.type == AccountType.CREDIT_CARD ? revolving : account.balanceCents;
            }
            double fixedReal = (renter ? rent : 0)
                    + bills.stream().mapToDouble(Bill::baseAmount).sum()
                    + subscriptions.stream().mapToDouble(Subscription::baseAmount).sum();
            double debtServiceReal = activeLoans.stream()
                    .mapToDouble(l -> InstallmentScheduler.monthlyPayment(l.principal, l.monthlyRate, l.termMonths)
                            .doubleValue() / macro.priceIndex(YearMonth.from(l.disbursedAt)))
                    .sum();
            double totalSpend = baseIncome * profile.spendRatio() * spendingStyle;
            discretionaryRealBase = Math.max(totalSpend - fixedReal - 0.6 * debtServiceReal, 0.2 * totalSpend);
            habitReal = discretionaryRealBase;
        }

        private void createExistingLoan(LoanType type, int tenureMonths) {
            boolean mortgage = type == LoanType.MORTGAGE;
            int term = mortgage ? new int[]{60, 84, 96, 120}[r.intBetween(0, 3)] : new int[]{12, 24, 36}[r.intBetween(0, 2)];
            int maxElapsed = Math.min(term - 1, Math.min(tenureMonths - 1, mortgage ? 72 : term - 1));
            if (maxElapsed < 1) {
                return;
            }
            int elapsed = r.intBetween(1, maxElapsed);
            LocalDate disbursed = startMonth.minusMonths(elapsed).atDay(r.intBetween(1, 28));
            double incomeThen = baseIncome * macro.priceIndex(YearMonth.from(disbursed));
            double policy = macro.policyRate(YearMonth.from(disbursed));
            Sim.Loan loan = mortgage
                    ? originate(type, incomeThen * r.uniform(0.2, 0.35), term, mortgageRate(policy), disbursed)
                    : originatePersonal(incomeThen, incomeThen * r.uniform(1, 5), policy, disbursed, term);
            if (loan == null) {
                return;
            }
            for (Installment inst : loan.installments) {
                if (inst.dueDate.isBefore(startMonth.atDay(1))) {
                    inst.paidCents = inst.totalCents();
                    inst.paidAt = inst.dueDate;
                }
            }
        }

        private void initialiseStress() {
            stress = 0.2;
            for (int i = 0; i < 4; i++) {
                stress = StressModel.nextStress(stress, drivers(0), 0);
            }
        }

        // ---------------------------------------------------------------- monthly cycle

        private void beginMonth(YearMonth ym) {
            boolean firstMonth = month == null;
            month = ym;
            monthIndex = (int) ChronoUnit.MONTHS.between(startMonth, ym);
            monthIncome = 0;
            monthDebtService = 0;
            dayPlans = new DayPlan[ym.lengthOfMonth() + 1];
            for (int i = 1; i < dayPlans.length; i++) {
                dayPlans[i] = new DayPlan();
            }
            if (!firstMonth) {
                applyRaises(ym);
                adjustCardLimit(ym);
                if (renter && ym.getMonth() == rentAnniversary) {
                    rent *= 1 + macro.cumulativeInflation(ym.minusMonths(13), ym.minusMonths(1));
                }
            }
            updateEmployment(ym);
            updateChurnIntent(ym);
            double activity = activityFactor();

            planIncome(ym);
            planFixedCosts(ym);
            planLoans(ym);
            planNewCredit(ym);
            if (card != null && card.closedAt == null) {
                LocalDate statementDate = ym.atDay(card.statementDay);
                plan(statementDate).fixed.add(() -> cardStatement(statementDate));
            }
            double budget = discretionaryBudget(ym, activity);
            planDiscretionary(ym, budget, activity);
            planCash(ym, budget, activity);
            planTransfers(ym, budget, activity);
            planBigTicket(ym, activity);
            if (churnIntent && monthIndex == churnMonthIndex) {
                LocalDate date = ym.atDay(r.intBetween(5, 25));
                plan(date).other.add(() -> executeChurn(date));
            }
        }

        private void processDay(LocalDate d) {
            if (!d.isBefore(joinDate)) {
                DayPlan dp = dayPlans[d.getDayOfMonth()];
                runAll(dp.income);
                runAll(dp.fixed);
                if (active && pendingStatement != null && d.equals(pendingStatement.dueDate)) {
                    cardDue(d);
                }
                runAll(dp.spending);
                runAll(dp.other);
            }
            if (active && daysPastDue(d) >= DEFAULT_DAYS_PAST_DUE) {
                declareDefault(d);
            }
        }

        private void runAll(List<Runnable> actions) {
            for (Runnable action : actions) {
                if (!active) {
                    return;
                }
                action.run();
            }
        }

        private void endMonth() {
            sweepSavings(month);
            accrueDepositInterest(month);
            incomeHistory.addLast(monthIncome);
            if (incomeHistory.size() > 6) {
                incomeHistory.removeFirst();
            }
            StressModel.Drivers drivers = drivers(monthIncome);
            stress = StressModel.nextStress(stress, drivers, r.normal(0, 0.25));
            stressSum += stress;
            maxStress = Math.max(maxStress, stress);
            double pChurn = churnEligible()
                    ? StressModel.churnHazard(stress, churnPropensity, profile.churnLogitOffset(), tenureMonths())
                    : 0.0;
            h.monthlyStates.add(new Sim.MonthlyState(month, stress, drivers.debtToIncome(), drivers.realIncomeLoss(),
                    drivers.cardUtilization(), drivers.incomeVolatility(), drivers.bufferMonths(), monthIncome,
                    StressModel.missProbability(stress), pChurn, daysPastDue(month.atEndOfMonth()), isUnemployed(),
                    churnIntent));
        }

        private StressModel.Drivers drivers(long incomeThisMonth) {
            YearMonth ym = month == null ? startMonth : month;
            double priceIndex = macro.priceIndex(ym);
            double income = incomeHistory.isEmpty()
                    ? regularIncome
                    : incomeHistory.stream().skip(Math.max(0, incomeHistory.size() - 3L))
                    .mapToLong(Long::longValue).average().orElse(0) / 100.0;
            double debtService = month == null
                    ? activeLoans.stream().mapToDouble(this::currentInstallmentAmount).sum()
                    + minimumPaymentOf(revolving) / 100.0
                    : monthDebtService / 100.0;
            double incomeForDti = month == null ? regularIncome : Math.max(incomeThisMonth / 100.0, 1_000 * priceIndex);
            double dti = debtService / Math.max(incomeForDti, 1.0);
            double realIncomeLoss = 1 - (income / priceIndex) / baseIncome;
            double utilization = card == null || card.creditLimitCents == 0 ? 0
                    : (double) cardOutstanding() / card.creditLimitCents;
            double volatility = incomeVolatility();
            double liquid = checking.balanceCents + (deposit == null ? 0 : deposit.balanceCents)
                    + (usd == null ? 0 : usd.balanceCents * macro.usdTry(ym));
            double buffer = liquid / 100.0 / Math.max(regularIncome, 1.0);
            int dpd = month == null ? 0 : daysPastDue(ym.atEndOfMonth());
            return new StressModel.Drivers(dti, realIncomeLoss, utilization, volatility, resilience, buffer, dpd);
        }

        private double incomeVolatility() {
            if (incomeHistory.size() < 3) {
                return incomeVolatilityBase;
            }
            double mean = incomeHistory.stream().mapToLong(Long::longValue).average().orElse(0);
            if (mean <= 0) {
                return 1.5;
            }
            double variance = incomeHistory.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
            return Math.sqrt(variance) / mean;
        }

        // ---------------------------------------------------------------- income

        private void applyRaises(YearMonth ym) {
            Month m = ym.getMonth();
            switch (profile.segment()) {
                case MIN_WAGE -> regularIncome = minimumWage(ym.getYear()) * minWageMultiplier;
                case WHITE_COLLAR -> {
                    if (m == Month.JANUARY) {
                        regularIncome *= 1 + pastInflation(ym, 12) * passThrough;
                        julyRaise = r.chance(0.5);
                    } else if (m == Month.JULY && julyRaise) {
                        regularIncome *= 1 + pastInflation(ym, 6) * passThrough * 0.5;
                    }
                }
                case RETIREE -> {
                    if (m == Month.JANUARY || m == Month.JULY) {
                        regularIncome *= 1 + pastInflation(ym, 6);
                    }
                }
                case SELF_EMPLOYED -> regularIncome = baseIncome
                        * Math.pow(macro.priceIndex(ym.minusMonths(selfEmployedLag)), passThrough);
                case STUDENT -> {
                    if (m == Month.OCTOBER) {
                        regularIncome *= 1 + pastInflation(ym, 12) * passThrough;
                    }
                }
            }
        }

        private double pastInflation(YearMonth ym, int monthsBack) {
            return macro.cumulativeInflation(ym.minusMonths(monthsBack + 1L), ym.minusMonths(1));
        }

        private double minimumWage(int year) {
            if (year <= 2026) {
                return SegmentProfile.minimumWage(year);
            }
            return minimumWage(year - 1) * (1 + macro.cumulativeInflation(YearMonth.of(year - 2, 12),
                    YearMonth.of(year - 1, 12)));
        }

        private void updateEmployment(YearMonth ym) {
            if (unemployedMonthsLeft > 0) {
                unemployedMonthsLeft--;
                unemployedMonthsElapsed++;
                unemploymentMonths++;
                if (unemployedMonthsLeft == 0) {
                    if (profile.segment() == Segment.MIN_WAGE) {
                        minWageMultiplier *= r.uniform(0.95, 1.05);
                    } else {
                        regularIncome *= r.uniform(0.85, 1.05);
                    }
                }
                return;
            }
            if (publicSector || profile.monthlyJobLossRate() == 0 || monthIndex == joinMonthIndex) {
                return;
            }
            if (r.chance(profile.monthlyJobLossRate())) {
                double meanDuration = switch (profile.segment()) {
                    case WHITE_COLLAR -> 6;
                    case SELF_EMPLOYED -> 4;
                    default -> 5;
                };
                unemployedMonthsLeft = Math.min(r.geometric(meanDuration), 18);
                unemployedMonthsElapsed = 0;
                double tenureYears = ChronoUnit.DAYS.between(h.customerSince, ym.atDay(1)) / 365.0;
                if (profile.segment() != Segment.SELF_EMPLOYED && employer != null
                        && (profile.segment() == Segment.WHITE_COLLAR || r.chance(0.5))) {
                    double severance = regularIncome * Math.min(Math.max(tenureYears, 0.5), 10)
                            * (profile.segment() == Segment.WHITE_COLLAR ? 0.8 : 0.6);
                    LocalDate day = ym.atDay(r.intBetween(1, 10));
                    String text = DescriptionNoiser.fold("KIDEM TAZMINATI " + employer);
                    plan(day).income.add(() -> creditIncome(day, Sim.toCents(severance), Category.SALARY,
                            Channel.EFT_FAST, DescriptionNoiser.truncateDescription(text)));
                }
            }
        }

        private boolean isUnemployed() {
            return unemployedMonthsLeft > 0;
        }

        private void planIncome(YearMonth ym) {
            Segment segment = profile.segment();
            if (isUnemployed()) {
                if (segment == Segment.SELF_EMPLOYED) {
                    planSelfEmployedIncome(ym, regularIncome * r.uniform(0.2, 0.5));
                } else if (unemployedMonthsElapsed < 6) {
                    LocalDate day = TurkishCalendar.previousBusinessDay(ym.atDay(r.intBetween(5, 10)));
                    long amount = Sim.toCents(regularIncome * 0.4);
                    plan(day).income.add(() -> creditIncome(day, amount, Category.SALARY, Channel.EFT_FAST,
                            r.pick(new String[]{"ISKUR ISSIZLIK ODENEGI", "ISSIZLIK SIGORTASI ODEME ISKUR"})));
                } else {
                    LocalDate day = ym.atDay(r.intBetween(1, 28));
                    long amount = Sim.toCents(regularIncome * 0.15);
                    plan(day).income.add(() -> creditIncome(day, amount, Category.TRANSFER, Channel.EFT_FAST,
                            DescriptionNoiser.incomingTransfer(Names.personName(r), "DESTEK", r)));
                }
                return;
            }
            switch (segment) {
                case MIN_WAGE, WHITE_COLLAR -> {
                    LocalDate day = salaryDate(ym);
                    double noise = segment == Segment.MIN_WAGE ? r.normal(1.0, 0.03) : 1.0;
                    long amount = Sim.toCents(regularIncome * Math.max(noise, 0.85));
                    String text = DescriptionNoiser.salary(employer, ym, r);
                    plan(day).income.add(() -> creditIncome(day, amount, Category.SALARY, Channel.EFT_FAST, text));
                    if (segment == Segment.WHITE_COLLAR
                            && (ym.getMonth() == Month.MARCH || ym.getMonth() == Month.DECEMBER) && r.chance(0.2)) {
                        long bonus = Sim.toCents(regularIncome * r.uniform(0.4, 1.2));
                        String bonusText = DescriptionNoiser.fold("PRIM ODEMESI " + employer);
                        plan(day).income.add(() -> creditIncome(day, bonus, Category.SALARY, Channel.EFT_FAST,
                                DescriptionNoiser.truncateDescription(bonusText)));
                    }
                }
                case RETIREE -> {
                    LocalDate day = salaryDate(ym);
                    long amount = Sim.toCents(regularIncome);
                    String text = DescriptionNoiser.pension(ym, r);
                    plan(day).income.add(() -> creditIncome(day, amount, Category.SALARY, Channel.EFT_FAST, text));
                    for (TurkishCalendar.Holiday holiday : TurkishCalendar.RELIGIOUS_HOLIDAYS) {
                        if (YearMonth.from(holiday.start()).equals(ym)) {
                            LocalDate bonusDay = holiday.start().minusDays(5).getMonth() == ym.getMonth()
                                    ? holiday.start().minusDays(5) : ym.atDay(1);
                            long bonus = Sim.toCents(retireeHolidayBonus(ym.getYear()));
                            plan(bonusDay).income.add(() -> creditIncome(bonusDay, bonus, Category.SALARY,
                                    Channel.EFT_FAST, "SGK BAYRAM IKRAMIYESI"));
                        }
                    }
                }
                case SELF_EMPLOYED -> planSelfEmployedIncome(ym, regularIncome
                        * r.lognormalMean(1.0, incomeVolatilityBase) * selfEmployedSeasonality(ym));
                case STUDENT -> {
                    LocalDate day = salaryDate(ym);
                    long amount = Sim.toCents(regularIncome * (receivesScholarship ? 0.65 : 1.0));
                    if (employer != null) {
                        String text = DescriptionNoiser.salary(employer, ym, r);
                        plan(day).income.add(() -> creditIncome(day, amount, Category.SALARY, Channel.EFT_FAST, text));
                    } else {
                        String text = DescriptionNoiser.incomingTransfer(Names.personName(r), "HARCLIK", r);
                        plan(day).income.add(() -> creditIncome(day, amount, Category.TRANSFER, Channel.EFT_FAST, text));
                    }
                    int mv = ym.getMonthValue();
                    if (receivesScholarship && (mv >= 10 || mv <= 6)) {
                        LocalDate kykDay = TurkishCalendar.previousBusinessDay(ym.atDay(5));
                        long kyk = Sim.toCents(scholarshipAmount(ym));
                        plan(kykDay).income.add(() -> creditIncome(kykDay, kyk, Category.SALARY, Channel.EFT_FAST,
                                DescriptionNoiser.scholarship(r)));
                    }
                }
            }
        }

        private void planSelfEmployedIncome(YearMonth ym, double total) {
            int parts = r.intBetween(2, 6);
            double[] weights = new double[parts];
            for (int i = 0; i < parts; i++) {
                weights[i] = r.uniform(0.3, 1.0);
            }
            double sum = Arrays.stream(weights).sum();
            for (int i = 0; i < parts; i++) {
                LocalDate day = ym.atDay(r.intBetween(1, ym.lengthOfMonth()));
                long amount = Sim.toCents(total * weights[i] / sum);
                if (amount <= 0) {
                    continue;
                }
                String counterparty = r.chance(0.5) ? Names.personName(r) : Names.company(r, false);
                String text = DescriptionNoiser.incomingTransfer(counterparty,
                        r.pick(new String[]{"FATURA ODEMESI", "HIZMET BEDELI", "MAL BEDELI", ""}), r);
                plan(day).income.add(() -> creditIncome(day, amount, Category.TRANSFER, Channel.EFT_FAST, text));
            }
        }

        private double selfEmployedSeasonality(YearMonth ym) {
            return switch (ym.getMonth()) {
                case DECEMBER -> 1.15;
                case JANUARY, FEBRUARY -> 0.85;
                case JULY, AUGUST -> h.occupation == OccupationGroup.SHOP_OWNER ? 1.1 : 0.95;
                default -> 1.0;
            };
        }

        private double retireeHolidayBonus(int year) {
            return switch (year) {
                case 2023, 2024 -> 3_000;
                case 2025, 2026 -> 4_000;
                default -> 4_000 * Math.pow(1.25, year - 2026);
            };
        }

        private double scholarshipAmount(YearMonth ym) {
            int academicYear = ym.getMonthValue() >= 9 ? ym.getYear() : ym.getYear() - 1;
            return switch (academicYear) {
                case 2023 -> 2_000;
                case 2024 -> 3_000;
                case 2025 -> 4_000;
                default -> 4_000 * Math.pow(1.2, academicYear - 2025);
            };
        }

        private LocalDate salaryDate(YearMonth ym) {
            int day = Math.min(salaryDay == null ? 1 : salaryDay, ym.lengthOfMonth());
            LocalDate date = TurkishCalendar.previousBusinessDay(ym.atDay(day));
            return date.getMonth() == ym.getMonth() ? date : ym.atDay(day);
        }

        private void creditIncome(LocalDate d, long amount, Category category, Channel channel, String text) {
            if (amount <= 0) {
                return;
            }
            txn(checking, null, amount, Direction.CREDIT, channel, 1, text, at(d, 1, 9), category);
            checking.balanceCents += amount;
            monthIncome += amount;
            tryCure(d);
        }

        // ---------------------------------------------------------------- fixed costs

        private void planFixedCosts(YearMonth ym) {
            double priceIndex = macro.priceIndex(ym);
            if (renter) {
                LocalDate day = ym.atDay(rentDay);
                long amount = Sim.toCents(rent);
                plan(day).fixed.add(() -> payRent(day, amount));
            }
            for (Bill bill : bills) {
                double seasonal = switch (bill.seasonality()) {
                    case Bill.GAS -> TurkishCalendar.gasSeasonality(ym);
                    case Bill.ELECTRICITY -> TurkishCalendar.electricitySeasonality(ym);
                    default -> 1.0;
                };
                long amount = Sim.toCents(bill.baseAmount() * priceIndex * seasonal * r.lognormalMean(1.0, 0.12));
                LocalDate day = ym.atDay(Math.min(bill.day(), ym.lengthOfMonth()));
                plan(day).fixed.add(() -> payBill(day, bill, amount));
            }
            for (Subscription s : subscriptions) {
                LocalDate day = ym.atDay(s.day());
                long amount = Sim.toCents(s.baseAmount() * priceIndex);
                plan(day).fixed.add(() -> paySubscription(day, s, amount));
            }
        }

        private void payRent(LocalDate d, long amount) {
            if (!ensureChecking(amount, d)) {
                return;
            }
            checking.balanceCents -= amount;
            txn(checking, null, amount, Direction.DEBIT, Channel.EFT_FAST, 1,
                    DescriptionNoiser.rent(landlord, YearMonth.from(d), r), at(d, 8, 22), Category.RENT);
        }

        private void payBill(LocalDate d, Bill bill, long amount) {
            String text = DescriptionNoiser.bill(bill.biller().descriptors().getFirst(), bill.subscriberNo(), r);
            if (autoPayFromCard && cardUsable() && cardAvailable() >= amount) {
                cycle += amount;
                txn(card, bill.biller(), amount, Direction.DEBIT, Channel.AUTO_PAYMENT, 1, text, at(d, 1, 7),
                        bill.category());
            } else if (ensureChecking(amount, d)) {
                checking.balanceCents -= amount;
                txn(checking, bill.biller(), amount, Direction.DEBIT, Channel.AUTO_PAYMENT, 1, text, at(d, 1, 7),
                        bill.category());
            }
        }

        private void paySubscription(LocalDate d, Subscription s, long amount) {
            String text = DescriptionNoiser.purchase(s.merchant().descriptors().getFirst(), 1, r);
            if (cardUsable() && cardAvailable() >= amount) {
                cycle += amount;
                txn(card, s.merchant(), amount, Direction.DEBIT, Channel.ECOMMERCE, 1, text, at(d, 0, 6),
                        Category.ENTERTAINMENT);
            } else if (checking.balanceCents >= amount) {
                checking.balanceCents -= amount;
                txn(checking, s.merchant(), amount, Direction.DEBIT, Channel.ECOMMERCE, 1, text, at(d, 0, 6),
                        Category.ENTERTAINMENT);
            }
        }

        // ---------------------------------------------------------------- discretionary spending

        private double discretionaryBudget(YearMonth ym, double activity) {
            double priceIndex = macro.priceIndex(ym);
            double currentIncome = incomeHistory.isEmpty() ? regularIncome
                    : incomeHistory.stream().mapToLong(Long::longValue).average().orElse(0) / 100.0;
            double incomeRatio = Math.clamp((currentIncome / priceIndex) / baseIncome, 0.2, 2.0);
            double targetReal = discretionaryRealBase * (0.6 + 0.4 * incomeRatio);
            habitReal = 0.85 * habitReal + 0.15 * targetReal;
            return habitReal * priceIndex * (1 - 0.3 * stress) * activity * (isUnemployed() ? 0.8 : 1.0);
        }

        private void planDiscretionary(YearMonth ym, double budget, double activity) {
            double fxAdjustment = 0.5 + 0.5 * macro.fxIndex(ym) / macro.priceIndex(ym);
            double totalShare = 0;
            for (Map.Entry<Category, CategorySpec> e : SPECS.entrySet()) {
                if (e.getKey() != Category.FUEL || hasCar) {
                    totalShare += e.getValue().budgetShare() * profile.countMultiplier(e.getKey());
                }
            }
            int length = ym.lengthOfMonth();
            for (Map.Entry<Category, CategorySpec> e : SPECS.entrySet()) {
                Category category = e.getKey();
                CategorySpec spec = e.getValue();
                if (category == Category.FUEL && !hasCar) {
                    continue;
                }
                double multiplier = profile.countMultiplier(category);
                double baseCount = spec.monthlyCount() * multiplier
                        * (category == Category.TRANSPORT && !hasCar ? 1.6 : 1.0);
                double meanTicket = budget * spec.budgetShare() * multiplier / totalShare / baseCount;
                if (category == Category.ELECTRONICS || category == Category.TRAVEL) {
                    meanTicket *= fxAdjustment;
                }
                double[] weights = new double[length];
                double weightSum = 0;
                for (int day = 1; day <= length; day++) {
                    LocalDate date = ym.atDay(day);
                    weights[day - 1] = TurkishCalendar.dayMultiplier(category, date) * paydayFactor(date);
                    weightSum += weights[day - 1];
                }
                double lambda = baseCount * (weightSum / length) * TurkishCalendar.seasonalMultiplier(category, ym)
                        * activity;
                int count = r.poisson(lambda);
                double minimum = 5 * macro.priceIndex(ym);
                for (int i = 0; i < count; i++) {
                    LocalDate date = ym.atDay(r.pickWeighted(weights) + 1);
                    double amount = Math.max(r.lognormalMean(meanTicket, spec.ticketSigma()), minimum);
                    plan(date).spending.add(() -> purchase(date, category, amount, false));
                }
            }
        }

        private void planBigTicket(YearMonth ym, double activity) {
            boolean summer = ym.getMonth() == Month.JULY || ym.getMonth() == Month.AUGUST;
            boolean holidayMonth = TurkishCalendar.RELIGIOUS_HOLIDAYS.stream()
                    .anyMatch(hd -> YearMonth.from(hd.start()).equals(ym));
            double p = (0.04 + (summer ? 0.04 : 0) + (holidayMonth ? 0.03 : 0)) * (1 - 0.5 * stress) * activity;
            if (!r.chance(p)) {
                return;
            }
            double[] weights = {0.4, summer || holidayMonth ? 0.6 : 0.3, 0.1, 0.1,
                    ym.getMonth() == Month.SEPTEMBER ? 0.3 : 0.1};
            Category category = BIG_TICKET_CATEGORIES.get(r.pickWeighted(weights));
            double amount = regularIncome * r.lognormalMedian(0.5, 0.6);
            LocalDate date = ym.atDay(r.intBetween(1, ym.lengthOfMonth()));
            plan(date).spending.add(() -> purchase(date, category, amount, true));
        }

        private void purchase(LocalDate d, Category category, double amountTry, boolean big) {
            long amount = Sim.toCents(amountTry);
            CategorySpec spec = SPECS.get(category);
            boolean online = category == Category.ECOMMERCE
                    || r.chance(Math.min(1.0, spec.onlineShare() * profile.onlineAffinity()));
            MerchantRef merchant = online ? pickOnline(category) : pickLocal(category);
            if (merchant == null) {
                online = !online;
                merchant = online ? pickOnline(category) : pickLocal(category);
                if (merchant == null) {
                    return;
                }
            }
            boolean preferCard = cardUsable() && (big || r.chance(profile.cardSpendShare()));
            Sim.Account from = null;
            if (preferCard && cardAvailable() >= amount) {
                from = card;
            } else if (checking.balanceCents >= amount) {
                from = checking;
            } else if (cardUsable() && cardAvailable() >= amount) {
                from = card;
            }
            if (from == null) {
                return;
            }
            int installments = 1;
            Double propensity = INSTALLMENT_PROPENSITY.get(category);
            if (from == card && propensity != null
                    && amountTry >= INSTALLMENT_MIN_AMOUNT * referenceScale * macro.priceIndex(month)
                    && r.chance(Math.min(0.95, (big ? propensity + 0.25 : propensity) + 0.3 * stress))) {
                installments = INSTALLMENT_OPTIONS[r.pickWeighted(INSTALLMENT_WEIGHTS)];
            }
            if (from == card) {
                if (installments > 1) {
                    long per = amount / installments;
                    cardPlans.add(new CardPlan(per, amount - per * installments, installments));
                } else {
                    cycle += amount;
                }
            } else {
                checking.balanceCents -= amount;
            }
            String text = DescriptionNoiser.purchase(r.pick(merchant.descriptors()), installments, r);
            txn(from, merchant, amount, Direction.DEBIT, online ? Channel.ECOMMERCE : Channel.POS, installments, text,
                    at(d, spec.fromHour(), spec.toHour()), category);
        }

        private MerchantRef pickLocal(Category category) {
            List<MerchantRef> all = catalog.local(city, category);
            if (all.isEmpty()) {
                return null;
            }
            List<MerchantRef> favourites = favorites.computeIfAbsent(category, c -> {
                List<MerchantRef> shuffled = new ArrayList<>(all);
                for (int i = shuffled.size() - 1; i > 0; i--) {
                    Collections.swap(shuffled, i, r.intBetween(0, i));
                }
                return List.copyOf(shuffled.subList(0, Math.min(6, shuffled.size())));
            });
            return r.chance(0.85) ? favourites.get(r.zipf(favourites.size(), 1.1)) : r.pick(all);
        }

        private MerchantRef pickOnline(Category category) {
            List<MerchantRef> all = catalog.online(category);
            return all.isEmpty() ? null : all.get(r.zipf(all.size(), 1.0));
        }

        private void planCash(YearMonth ym, double budget, double activity) {
            double lambda = switch (profile.segment()) {
                case STUDENT -> 1.5;
                case MIN_WAGE -> 2.5;
                case WHITE_COLLAR -> 1.0;
                case RETIREE -> 3.0;
                case SELF_EMPLOYED -> 2.0;
            };
            int count = r.poisson(lambda * activity);
            double mean = budget * profile.cashShare() / lambda;
            for (int i = 0; i < count; i++) {
                LocalDate date = ym.atDay(r.intBetween(1, ym.lengthOfMonth()));
                double amount = Math.max(100, Math.round(r.lognormalMean(mean, 0.5) / 50) * 50.0);
                plan(date).spending.add(() -> withdrawCash(date, Sim.toCents(amount)));
            }
        }

        private void withdrawCash(LocalDate d, long amount) {
            String district = r.pick(city.districts());
            if (checking.balanceCents >= amount) {
                checking.balanceCents -= amount;
                txn(checking, null, amount, Direction.DEBIT, Channel.ATM, 1,
                        DescriptionNoiser.atmWithdrawal(district, city.abbreviation(), false, r), at(d, 8, 23),
                        Category.CASH_WITHDRAWAL);
            } else if (stress > 0.45 && cardUsable() && cardAvailable() >= amount && r.chance(0.5)) {
                cycle += amount;
                txn(card, null, amount, Direction.DEBIT, Channel.ATM, 1,
                        DescriptionNoiser.atmWithdrawal(district, city.abbreviation(), true, r), at(d, 8, 23),
                        Category.CASH_WITHDRAWAL);
            }
        }

        private void planTransfers(YearMonth ym, double budget, double activity) {
            int outgoing = r.poisson(1.2 * activity);
            for (int i = 0; i < outgoing; i++) {
                LocalDate date = ym.atDay(r.intBetween(1, ym.lengthOfMonth()));
                long amount = Sim.toCents(Math.max(50, r.lognormalMean(budget * 0.05 / 1.2, 0.8)));
                String text = DescriptionNoiser.outgoingTransfer(Names.personName(r), r.pick(TRANSFER_PURPOSES), r);
                plan(date).spending.add(() -> {
                    if (checking.balanceCents >= amount) {
                        checking.balanceCents -= amount;
                        txn(checking, null, amount, Direction.DEBIT, Channel.EFT_FAST, 1, text, at(date, 8, 23),
                                Category.TRANSFER);
                    }
                });
            }
            int incoming = r.poisson(0.5 * activity);
            for (int i = 0; i < incoming; i++) {
                LocalDate date = ym.atDay(r.intBetween(1, ym.lengthOfMonth()));
                long amount = Sim.toCents(Math.max(50, r.lognormalMean(budget * 0.03, 0.8)));
                String text = DescriptionNoiser.incomingTransfer(Names.personName(r), r.pick(TRANSFER_PURPOSES), r);
                plan(date).income.add(() -> {
                    checking.balanceCents += amount;
                    txn(checking, null, amount, Direction.CREDIT, Channel.EFT_FAST, 1, text, at(date, 8, 23),
                            Category.TRANSFER);
                });
            }
        }

        private double paydayFactor(LocalDate date) {
            if (salaryDay == null) {
                return 1.0;
            }
            int length = date.lengthOfMonth();
            int sinceSalary = Math.floorMod(date.getDayOfMonth() - Math.min(salaryDay, length), length);
            if (sinceSalary < 5) {
                return 1.3;
            }
            return sinceSalary >= length - 5 ? 0.75 : 1.0;
        }

        private double activityFactor() {
            if (!churnIntent || churnMonthIndex < 0) {
                return 1.0;
            }
            return switch (churnMonthIndex - monthIndex) {
                case 0 -> 0.2;
                case 1 -> 0.4;
                case 2 -> 0.65;
                default -> 0.85;
            };
        }

        // ---------------------------------------------------------------- savings

        private void sweepSavings(YearMonth ym) {
            if (churnIntent) {
                return;
            }
            long threshold = Sim.toCents(regularIncome * 1.3);
            long excess = checking.balanceCents - threshold;
            if (excess <= 0) {
                return;
            }
            LocalDate d = ym.atEndOfMonth();
            if (deposit == null && excess > threshold && r.chance(0.1)) {
                openDeposit(d, 0);
            }
            if (deposit != null && deposit.closedAt == null) {
                long move = excess / 2;
                checking.balanceCents -= move;
                deposit.balanceCents += move;
                transfer(checking, move, DescriptionNoiser.depositTransfer(true, r), deposit, move,
                        DescriptionNoiser.depositTransfer(true, r), at(d, 18, 23), Channel.EFT_FAST, Category.TRANSFER);
                excess -= move;
            }
            if (usd != null && usd.closedAt == null && r.chance(0.35)) {
                long tryAmount = excess / 5;
                long usdAmount = Math.round(tryAmount / macro.usdTry(ym));
                if (usdAmount > 0) {
                    checking.balanceCents -= tryAmount;
                    usd.balanceCents += usdAmount;
                    transfer(checking, tryAmount, DescriptionNoiser.fxPurchase(false, r), usd, usdAmount,
                            DescriptionNoiser.fxPurchase(true, r), at(d, 10, 17), Channel.EFT_FAST, Category.FX);
                }
            }
        }

        private void accrueDepositInterest(YearMonth ym) {
            if (deposit == null || deposit.closedAt != null || deposit.balanceCents <= 0) {
                return;
            }
            double rate = Math.max(macro.policyRate(ym) - 0.04, 0);
            // 32-day rollover with interest capitalised, net of withholding tax, posted as a bank transaction.
            long interest = Math.round(deposit.balanceCents * rate / 12 * 0.85);
            deposit.balanceCents += interest;
            txn(deposit, null, interest, Direction.CREDIT, Channel.SYSTEM, 1,
                    DescriptionNoiser.depositInterest(r), at(ym.atEndOfMonth(), 23, 24), Category.INTEREST);
            deposit.interestRate = BigDecimal.valueOf(rate).setScale(6, RoundingMode.HALF_EVEN);
            deposit.maturityDate = ym.atEndOfMonth().plusDays(32);
        }

        /** Makes sure checking can cover {@code needed}, breaking the time deposit if necessary. */
        private boolean ensureChecking(long needed, LocalDate d) {
            if (checking.balanceCents >= needed) {
                return true;
            }
            if (deposit != null && deposit.closedAt == null && deposit.balanceCents > 0) {
                long move = Math.min(deposit.balanceCents, needed - checking.balanceCents);
                deposit.balanceCents -= move;
                checking.balanceCents += move;
                transfer(deposit, move, DescriptionNoiser.depositTransfer(false, r), checking, move,
                        DescriptionNoiser.depositTransfer(false, r), at(d, 0, 2), Channel.EFT_FAST, Category.TRANSFER);
            }
            return checking.balanceCents >= needed;
        }

        // ---------------------------------------------------------------- credit card

        private boolean cardUsable() {
            return card != null && card.closedAt == null && cardOldestUnpaid == null;
        }

        private long unbilledInstallments() {
            long sum = 0;
            for (CardPlan plan : cardPlans) {
                sum += plan.unbilled();
            }
            return sum;
        }

        private long cardOutstanding() {
            return Math.max(0, revolving) + cycle + unbilledInstallments();
        }

        private long cardAvailable() {
            return card.creditLimitCents - cardOutstanding();
        }

        private long minimumPaymentOf(long balance) {
            if (card == null || balance <= 0) {
                return 0;
            }
            double ratio = card.creditLimitCents < Sim.toCents(MIN_PAYMENT_LIMIT_THRESHOLD) ? 0.20 : 0.40;
            return Math.round(balance * ratio);
        }

        private void cardStatement(LocalDate d) {
            long billed = 0;
            for (var it = cardPlans.iterator(); it.hasNext(); ) {
                CardPlan plan = it.next();
                billed += plan.perInstallment + plan.firstExtra;
                plan.firstExtra = 0;
                plan.remaining--;
                if (plan.remaining <= 0) {
                    it.remove();
                }
            }
            double rate = cardMonthlyRate(macro.policyRate(month)) + (pastDue > 0 ? 0.003 : 0);
            long interest = revolving > 0 ? Math.round(revolving * rate) : 0;
            txn(card, null, interest, Direction.DEBIT, Channel.SYSTEM, 1,
                    DescriptionNoiser.cardInterest(pastDue > 0, r), at(d, 0, 1), Category.INTEREST);
            revolving += interest + cycle + billed;
            cycle = 0;
            if (revolving <= 0) {
                revolving = Math.max(revolving, 0);
                return;
            }
            long minimum = Math.min(revolving, minimumPaymentOf(revolving - pastDue) + pastDue);
            Statement statement = new Statement(card, d, d.plusDays(CARD_DUE_DAYS), revolving, minimum);
            h.statements.add(statement);
            pendingStatement = statement;
            monthDebtService += minimum;
        }

        private void cardDue(LocalDate d) {
            Statement statement = pendingStatement;
            pendingStatement = null;
            long full = Math.max(revolving, 0);
            // Payments made since the statement date count towards its minimum.
            long required = Math.min(Math.max(0, statement.minimumPaymentCents - statement.paidCents), full);
            if (required <= 0) {
                if (pastDue > 0 && full == 0) {
                    cureCard(d);
                }
                return;
            }
            long target;
            boolean unwilling = cardOldestUnpaid != null
                    ? !r.chance(StressModel.cureProbability(stress))
                    : r.chance(StressModel.missProbability(stress));
            if (unwilling) {
                target = r.chance(0.3) ? Math.round(required * r.uniform(0.1, 0.7)) : 0;
            } else {
                target = switch (payerType) {
                    case TRANSACTOR -> r.chance(0.92 - 0.6 * stress) ? full
                            : Math.max(required, Math.round(full * r.uniform(0.3, 0.8)));
                    case REVOLVER -> Math.max(required, Math.round(full * r.uniform(0.15, 0.6) * (1 - 0.5 * stress)));
                    case MIN_PAYER -> r.chance(0.75) ? required
                            : Math.max(required, Math.round(full * r.uniform(0.2, 0.4)));
                };
            }
            target = Math.min(target, full);
            if (target > 0) {
                ensureChecking(target, d);
            }
            long paid = Math.min(target, Math.max(checking.balanceCents, 0));
            if (paid > 0) {
                payCard(d, paid, autoPayFromCard, statement);
            }
            if (paid >= required) {
                cureCard(d);
            } else {
                pastDue = required - paid;
                if (cardOldestUnpaid == null) {
                    cardOldestUnpaid = statement.dueDate;
                    latePayment(d, card, null, "Credit card minimum payment missed");
                }
                delinquentStatements.add(statement);
                churnIntent = false;
            }
        }

        /** Pays {@code amount} from checking to the card, crediting it to {@code statement} when given. */
        private void payCard(LocalDate d, long amount, boolean automatic, Statement statement) {
            checking.balanceCents -= amount;
            revolving -= amount;
            if (statement != null) {
                statement.paidCents += amount;
                statement.paidAt = d;
            }
            OffsetDateTime time = automatic ? at(d, 1, 7) : at(d, 9, 22);
            Sim.Transaction debit = txn(checking, null, amount, Direction.DEBIT,
                    automatic ? Channel.AUTO_PAYMENT : Channel.EFT_FAST, 1,
                    DescriptionNoiser.cardPaymentFromAccount(cardLast4, automatic, r), time, Category.CARD_PAYMENT);
            Sim.Transaction credit = txn(card, null, amount, Direction.CREDIT, Channel.EFT_FAST, 1,
                    DescriptionNoiser.cardPaymentReceived(r), time, Category.CARD_PAYMENT);
            link(debit, credit);
        }

        private void cureCard(LocalDate d) {
            pastDue = 0;
            if (cardOldestUnpaid != null) {
                for (Statement s : delinquentStatements) {
                    s.daysPastDue = (int) ChronoUnit.DAYS.between(s.dueDate, d);
                }
                delinquentStatements.clear();
                cardOldestUnpaid = null;
            }
        }

        private void tryCure(LocalDate d) {
            if (pastDue > 0 && card.closedAt == null && r.chance(StressModel.cureProbability(stress))
                    && ensureChecking(pastDue, d)) {
                long amount = Math.min(pastDue, Math.max(revolving, 0));
                if (amount > 0) {
                    // The latest statement's minimum already includes the past-due amount, so it gets the credit.
                    payCard(d, amount, false,
                            pendingStatement != null ? pendingStatement : delinquentStatements.getLast());
                }
                cureCard(d);
            }
            for (Map.Entry<Sim.Loan, List<Installment>> e : loanArrears.entrySet()) {
                List<Installment> overdue = e.getValue();
                while (!overdue.isEmpty() && r.chance(StressModel.cureProbability(stress))
                        && ensureChecking(overdue.getFirst().totalCents(), d)) {
                    payInstallment(d, e.getKey(), overdue.removeFirst());
                }
            }
            closeFinishedLoans();
        }

        private void adjustCardLimit(YearMonth ym) {
            if (card == null || card.closedAt != null || cardOldestUnpaid != null) {
                return;
            }
            if ((ym.getMonth() == Month.JANUARY || ym.getMonth() == Month.JULY) && r.chance(0.5)) {
                double increase = pastInflation(ym, 6) * r.uniform(0.5, 1.0);
                card.creditLimitCents = Math.round(card.creditLimitCents * (1 + increase) / 50_000.0) * 50_000;
            }
        }

        // ---------------------------------------------------------------- loans

        private void planLoans(YearMonth ym) {
            for (Sim.Loan loan : List.copyOf(activeLoans)) {
                for (Installment inst : loan.installments) {
                    if (!inst.isPaid() && YearMonth.from(inst.dueDate).equals(ym)) {
                        LocalDate due = inst.dueDate;
                        plan(due).fixed.add(() -> loanDue(due, loan, inst));
                    }
                }
            }
        }

        private void loanDue(LocalDate d, Sim.Loan loan, Installment installment) {
            if (loan.status != LoanStatus.ACTIVE) {
                return;
            }
            monthDebtService += installment.totalCents();
            List<Installment> overdue = loanArrears.computeIfAbsent(loan, l -> new ArrayList<>());
            boolean delinquent = !overdue.isEmpty();
            overdue.add(installment);
            boolean willing = delinquent
                    ? r.chance(StressModel.cureProbability(stress))
                    : !r.chance(StressModel.missProbability(stress) * 0.4);
            if (willing) {
                while (!overdue.isEmpty() && ensureChecking(overdue.getFirst().totalCents(), d)) {
                    payInstallment(d, loan, overdue.removeFirst());
                }
            }
            if (!overdue.isEmpty() && overdue.getFirst() == installment && overdue.size() == 1) {
                latePayment(d, null, loan, "Loan installment " + installment.installmentNo + " missed");
                churnIntent = false;
            }
            closeFinishedLoans();
        }

        private void payInstallment(LocalDate d, Sim.Loan loan, Installment installment) {
            long amount = installment.totalCents();
            checking.balanceCents -= amount;
            installment.paidCents = amount;
            installment.paidAt = d;
            installment.daysPastDue = (int) Math.max(0, ChronoUnit.DAYS.between(installment.dueDate, d));
            txn(checking, null, amount, Direction.DEBIT, Channel.AUTO_PAYMENT, 1,
                    DescriptionNoiser.loanInstallment(loan.loanNo, installment.installmentNo, loan.termMonths, r),
                    at(d, 1, 7), Category.LOAN);
        }

        private void closeFinishedLoans() {
            for (var it = activeLoans.iterator(); it.hasNext(); ) {
                Sim.Loan loan = it.next();
                if (loan.installments.getLast().isPaid()) {
                    loan.status = LoanStatus.CLOSED;
                    loanArrears.remove(loan);
                    it.remove();
                }
            }
            loanArrears.values().removeIf(List::isEmpty);
        }

        private double currentInstallmentAmount(Sim.Loan loan) {
            return loan.installments.getFirst().totalCents() / 100.0;
        }

        private void planNewCredit(YearMonth ym) {
            if (churnIntent || pastDue > 0 || !loanArrears.isEmpty() || monthIndex == joinMonthIndex) {
                return;
            }
            double buffer = drivers(monthIncome).bufferMonths();
            long personalLoans = activeLoans.stream().filter(l -> l.type == LoanType.PERSONAL).count();
            double hazard = profile.personalLoanProbability() / 30
                    + 0.03 * stress * (buffer < 1 ? 1.0 : 0.3);
            if (personalLoans < 2 && r.chance(hazard)) {
                LocalDate day = ym.atDay(r.intBetween(1, 28));
                boolean distressed = stress > 0.5;
                double principal = regularIncome * (distressed ? r.uniform(0.5, 2) : r.uniform(1, 5));
                plan(day).income.add(() -> disburseLoan(day, principal));
            }
            Segment segment = profile.segment();
            if (renter && (segment == Segment.WHITE_COLLAR || segment == Segment.SELF_EMPLOYED)
                    && regularIncome >= 40_000 * macro.priceIndex(ym) * referenceScale && buffer >= 3
                    && activeLoans.stream().noneMatch(l -> l.type == LoanType.MORTGAGE) && r.chance(0.0012)) {
                LocalDate day = ym.atDay(r.intBetween(1, 28));
                plan(day).income.add(() -> disburseMortgage(day));
            }
        }

        private void disburseLoan(LocalDate d, double requestedPrincipal) {
            double policy = macro.policyRate(month);
            double existingService = activeLoans.stream().mapToDouble(this::currentInstallmentAmount).sum()
                    + minimumPaymentOf(revolving) / 100.0;
            if (stress >= 0.85 || cardOldestUnpaid != null) {
                return;
            }
            Sim.Loan loan = originatePersonal(regularIncome, requestedPrincipal, policy, d, 0);
            if (loan == null) {
                return;
            }
            double installment = currentInstallmentAmount(loan);
            if ((existingService + installment) / Math.max(regularIncome, 1) > 0.5) {
                h.loans.remove(loan);
                activeLoans.remove(loan);
                return;
            }
            long amount = Sim.toCents(loan.principal);
            checking.balanceCents += amount;
            txn(checking, null, amount, Direction.CREDIT, Channel.EFT_FAST, 1,
                    DescriptionNoiser.loanDisbursement(loan.loanNo, false, r), at(d, 9, 17), Category.LOAN);
            double use = r.uniform();
            if (use < 0.5) {
                Category category = List.of(Category.ELECTRONICS, Category.TRAVEL, Category.HEALTH,
                        Category.EDUCATION, Category.CLOTHING, Category.ECOMMERCE).get(r.pickWeighted(
                        new double[]{0.35, 0.2, 0.1, 0.1, 0.1, 0.15}));
                double spend = loan.principal.doubleValue() * r.uniform(0.6, 0.9);
                LocalDate when = d.plusDays(r.intBetween(0, 5));
                if (YearMonth.from(when).equals(month)) {
                    plan(when).spending.add(() -> purchase(when, category, spend, true));
                }
            } else if (use < 0.8 && card != null && revolving > 0) {
                long payDown = Math.min(revolving, amount);
                payCard(d, payDown, false, pendingStatement);
                if (payDown >= pastDue) {
                    cureCard(d);
                }
            }
        }

        private void disburseMortgage(LocalDate d) {
            Sim.Loan loan = originate(LoanType.MORTGAGE, regularIncome * r.uniform(0.2, 0.35),
                    new int[]{60, 84, 96, 120}[r.intBetween(0, 3)], mortgageRate(macro.policyRate(month)), d);
            if (loan == null) {
                return;
            }
            long amount = Sim.toCents(loan.principal);
            String seller = Names.personName(r);
            checking.balanceCents += amount;
            txn(checking, null, amount, Direction.CREDIT, Channel.EFT_FAST, 1,
                    DescriptionNoiser.loanDisbursement(loan.loanNo, true, r), at(d, 9, 12), Category.LOAN);
            checking.balanceCents -= amount;
            txn(checking, null, amount, Direction.DEBIT, Channel.EFT_FAST, 1,
                    DescriptionNoiser.outgoingTransfer(seller, "KONUT BEDELI", r), at(d, 12, 16), Category.TRANSFER);
            renter = false;
        }

        private BigDecimal mortgageRate(double policy) {
            return BigDecimal.valueOf(Math.max(policy / 12 - 0.012, 0.012) + r.uniform(0, 0.003))
                    .setScale(6, RoundingMode.HALF_EVEN);
        }

        /** Personal loan with BDDK maturity caps; shrinks the principal until the installment is affordable. */
        private Sim.Loan originatePersonal(double income, double requestedPrincipal, double policy,
                                           LocalDate disbursed, int fixedTerm) {
            double principal = Math.max(5_000, Math.round(requestedPrincipal / 1_000) * 1_000.0);
            BigDecimal rate = BigDecimal.valueOf(policy / 12 + r.uniform(0.002, 0.009))
                    .setScale(6, RoundingMode.HALF_EVEN);
            for (int attempt = 0; attempt < 10; attempt++) {
                int cap = maxPersonalLoanTerm(principal);
                int term = fixedTerm > 0 ? Math.min(fixedTerm, cap) : new int[]{12, 24, 36}[r.intBetween(0, 2)];
                term = Math.min(term, cap);
                BigDecimal amount = BigDecimal.valueOf(principal).setScale(2, RoundingMode.HALF_EVEN);
                double installment = InstallmentScheduler.monthlyPayment(amount, rate, term).doubleValue();
                if (installment <= 0.35 * income || principal <= 5_000) {
                    return register(LoanType.PERSONAL, amount, rate, term, disbursed);
                }
                principal = Math.max(5_000, Math.round(principal * 0.8 / 1_000) * 1_000.0);
            }
            return null;
        }

        /** Loan sized from a target installment. */
        private Sim.Loan originate(LoanType type, double targetInstallment, int term, BigDecimal rate,
                                   LocalDate disbursed) {
            BigDecimal factor = InstallmentScheduler.monthlyPayment(BigDecimal.valueOf(1_000_000), rate, term)
                    .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_EVEN);
            double principal = Math.round(targetInstallment / factor.doubleValue() / 1_000) * 1_000.0;
            if (principal < 10_000) {
                return null;
            }
            return register(type, BigDecimal.valueOf(principal).setScale(2, RoundingMode.HALF_EVEN), rate, term,
                    disbursed);
        }

        private Sim.Loan register(LoanType type, BigDecimal principal, BigDecimal rate, int term, LocalDate disbursed) {
            LocalDate safeDisbursed = disbursed.getDayOfMonth() > 28 ? disbursed.withDayOfMonth(28) : disbursed;
            Sim.Loan loan = new Sim.Loan(type, principal, rate, term, safeDisbursed, r.digits(10));
            for (ScheduledInstallment s : InstallmentScheduler.schedule(principal, rate, term, safeDisbursed)) {
                loan.installments.add(new Installment(s.installmentNo(), s.dueDate(), s.principal(), s.interest(),
                        s.remainingPrincipal()));
            }
            h.loans.add(loan);
            activeLoans.add(loan);
            return loan;
        }

        // ---------------------------------------------------------------- delinquency, default, churn

        private int daysPastDue(LocalDate d) {
            long dpd = cardOldestUnpaid == null ? 0 : ChronoUnit.DAYS.between(cardOldestUnpaid, d);
            for (List<Installment> overdue : loanArrears.values()) {
                if (!overdue.isEmpty()) {
                    dpd = Math.max(dpd, ChronoUnit.DAYS.between(overdue.getFirst().dueDate, d));
                }
            }
            return (int) Math.max(0, dpd);
        }

        private void latePayment(LocalDate d, Sim.Account account, Sim.Loan loan, String details) {
            latePayments++;
            h.events.add(new Sim.Event(CustomerEventType.LATE_PAYMENT, d.plusDays(1), account, loan, 1, details));
        }

        private void declareDefault(LocalDate d) {
            int dpd = daysPastDue(d);
            Sim.Account account = null;
            Sim.Loan loan = null;
            if (cardOldestUnpaid != null && ChronoUnit.DAYS.between(cardOldestUnpaid, d) >= DEFAULT_DAYS_PAST_DUE) {
                account = card;
            } else {
                loan = loanArrears.entrySet().stream()
                        .filter(e -> !e.getValue().isEmpty())
                        .min(Comparator.comparing(e -> e.getValue().getFirst().dueDate))
                        .map(Map.Entry::getKey).orElse(null);
            }
            h.events.add(new Sim.Event(CustomerEventType.DEFAULT, d, account, loan, dpd,
                    account != null ? "Credit card 90+ days past due" : "Loan 90+ days past due"));
            h.status = CustomerStatus.DEFAULTED;
            for (Sim.Loan l : activeLoans) {
                l.status = LoanStatus.DEFAULTED;
            }
            if (card != null && card.closedAt == null) {
                card.closedAt = d;
            }
            defaultDate = d;
            active = false;
        }

        private boolean churnEligible() {
            return active && !churnIntent && activeLoans.isEmpty() && pastDue == 0 && loanArrears.isEmpty()
                    && tenureMonths() >= 3;
        }

        private int tenureMonths() {
            YearMonth ym = month == null ? YearMonth.from(joinDate) : month;
            return (int) ChronoUnit.MONTHS.between(YearMonth.from(h.customerSince), ym);
        }

        private void updateChurnIntent(YearMonth ym) {
            if (monthIndex == joinMonthIndex || !churnEligible()) {
                return;
            }
            double hazard = StressModel.churnHazard(stress, churnPropensity, profile.churnLogitOffset(), tenureMonths());
            if (r.chance(hazard)) {
                churnIntent = true;
                churnMonthIndex = monthIndex + r.intBetween(1, 3);
            }
        }

        private void executeChurn(LocalDate d) {
            if (!churnIntent || !activeLoans.isEmpty()) {
                churnIntent = false;
                return;
            }
            if (card != null && card.closedAt == null) {
                long owed = cardOutstanding();
                if (owed > 0) {
                    if (!ensureChecking(owed, d)) {
                        churnIntent = false;
                        churnMonthIndex = -1;
                        return;
                    }
                    revolving += cycle + unbilledInstallments();
                    cycle = 0;
                    cardPlans.clear();
                    payCard(d, owed, false, pendingStatement);
                }
                pendingStatement = null;
                card.closedAt = d;
            }
            if (deposit != null && deposit.closedAt == null) {
                if (deposit.balanceCents > 0) {
                    ensureChecking(checking.balanceCents + deposit.balanceCents, d);
                }
                deposit.closedAt = d;
            }
            if (usd != null && usd.closedAt == null) {
                if (usd.balanceCents > 0) {
                    txn(usd, null, usd.balanceCents, Direction.DEBIT, Channel.EFT_FAST, 1,
                            DescriptionNoiser.accountClosing(r), at(d, 10, 16), Category.TRANSFER);
                    usd.balanceCents = 0;
                }
                usd.closedAt = d;
            }
            if (checking.balanceCents > 0) {
                txn(checking, null, checking.balanceCents, Direction.DEBIT, Channel.EFT_FAST, 1,
                        DescriptionNoiser.accountClosing(r), at(d, 16, 20), Category.TRANSFER);
                checking.balanceCents = 0;
            }
            checking.closedAt = d;
            h.events.add(new Sim.Event(CustomerEventType.CHURN, d, null, null, null, "Customer closed all accounts"));
            h.status = CustomerStatus.CHURNED;
            churnDate = d;
            active = false;
        }

        // ---------------------------------------------------------------- output

        private void finish(LocalDate end) {
            for (Statement s : delinquentStatements) {
                s.daysPastDue = (int) ChronoUnit.DAYS.between(s.dueDate, end);
            }
            for (Sim.Loan loan : h.loans) {
                for (Installment inst : loan.installments) {
                    if (!inst.isPaid() && inst.dueDate.isBefore(end)) {
                        inst.daysPastDue = (int) ChronoUnit.DAYS.between(inst.dueDate, end);
                    }
                }
            }
            if (card != null) {
                card.balanceCents = card.closedAt != null && h.status == CustomerStatus.CHURNED ? 0 : cardOutstanding();
            }
            h.latestIncomeCents = Sim.toCents(regularIncome);
            h.latent = new Sim.Latent(resilience, churnPropensity, payerType, spendingStyle, incomeVolatilityBase,
                    passThrough, salaryDay, Sim.toCents(baseIncome), hasCar, renter);
            int observed = h.monthlyStates.size();
            h.outcome = new Sim.Outcome(defaultDate != null, defaultDate, churnDate != null, churnDate, latePayments,
                    unemploymentMonths, maxStress, observed == 0 ? stress : stressSum / observed);
            h.transactions.sort(Comparator.comparing((Sim.Transaction t) -> t.occurredAt)
                    .thenComparingInt(t -> t.sequence));
        }

        // ---------------------------------------------------------------- helpers

        private DayPlan plan(LocalDate date) {
            return dayPlans[date.getDayOfMonth()];
        }

        private Sim.Account openAccount(AccountType type, Currency currency, LocalDate opened) {
            Sim.Account account = new Sim.Account(type, currency, opened);
            h.accounts.add(account);
            return account;
        }

        private void openDeposit(LocalDate opened, long balance) {
            double rate = Math.max(macro.policyRate(YearMonth.from(opened)) - 0.04, 0.01);
            deposit = openAccount(AccountType.TIME_DEPOSIT, Currency.TRY, opened);
            deposit.balanceCents = balance;
            deposit.interestRate = BigDecimal.valueOf(rate).setScale(6, RoundingMode.HALF_EVEN);
            deposit.maturityDate = opened.plusDays(32);
        }

        private LocalDate randomDateBetween(LocalDate from, LocalDate to) {
            long days = ChronoUnit.DAYS.between(from, to);
            return days <= 0 ? from : from.plusDays((long) (r.uniform() * days));
        }

        private OffsetDateTime at(LocalDate d, int fromHour, int toHour) {
            int second = r.intBetween(fromHour * 3600, toHour * 3600 - 1);
            return OffsetDateTime.of(d, LocalTime.ofSecondOfDay(second), ISTANBUL).withOffsetSameInstant(ZoneOffset.UTC);
        }

        private Sim.Transaction txn(Sim.Account account, MerchantRef merchant, long amount, Direction direction,
                                    Channel channel, int installments, String description, OffsetDateTime time,
                                    Category category) {
            if (amount <= 0) {
                return null;
            }
            Sim.Transaction t = new Sim.Transaction(account, merchant, amount, direction, channel, installments,
                    description, time, category, sequence++);
            h.transactions.add(t);
            return t;
        }

        /** Both legs of a transfer between the customer's own accounts: same instant, linked to each other. */
        private void transfer(Sim.Account from, long fromAmount, String fromText, Sim.Account to, long toAmount,
                              String toText, OffsetDateTime time, Channel channel, Category category) {
            link(txn(from, null, fromAmount, Direction.DEBIT, channel, 1, fromText, time, category),
                    txn(to, null, toAmount, Direction.CREDIT, channel, 1, toText, time, category));
        }

        private static void link(Sim.Transaction a, Sim.Transaction b) {
            if (a != null && b != null) {
                a.counterpart = b;
                b.counterpart = a;
            }
        }
    }
}
