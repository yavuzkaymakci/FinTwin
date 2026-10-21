package com.fintwin.generator.simulation;

import com.fintwin.account.domain.AccountType;
import com.fintwin.account.domain.Currency;
import com.fintwin.customer.domain.CustomerEventType;
import com.fintwin.customer.domain.CustomerStatus;
import com.fintwin.customer.domain.OccupationGroup;
import com.fintwin.customer.domain.Segment;
import com.fintwin.generator.catalog.Category;
import com.fintwin.generator.catalog.MerchantCatalog.MerchantRef;
import com.fintwin.loan.domain.LoanStatus;
import com.fintwin.loan.domain.LoanType;
import com.fintwin.transaction.domain.Channel;
import com.fintwin.transaction.domain.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory output of the simulation for one customer. Money is held in minor units (kuruş / cents) as
 * {@code long} — exact integer arithmetic, converted to {@code BigDecimal} with scale 2 when written. Database ids
 * are assigned by the writer ({@code id} fields), so the simulation itself is id-agnostic.
 */
public final class Sim {

    private Sim() {
    }

    public static BigDecimal toMoney(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    public static long toCents(double amount) {
        return Math.round(amount * 100.0);
    }

    public static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    public enum PayerType {
        TRANSACTOR,
        REVOLVER,
        MIN_PAYER
    }

    public static final class Account {
        public long id;
        public final AccountType type;
        public final Currency currency;
        public final LocalDate openedAt;
        public LocalDate closedAt;
        public long balanceCents;
        public BigDecimal interestRate;
        public LocalDate maturityDate;
        public long creditLimitCents;
        public Integer statementDay;
        public Integer dueDay;

        public Account(AccountType type, Currency currency, LocalDate openedAt) {
            this.type = type;
            this.currency = currency;
            this.openedAt = openedAt;
        }
    }

    public static final class Installment {
        public long id;
        public final int installmentNo;
        public final LocalDate dueDate;
        public final BigDecimal principal;
        public final BigDecimal interest;
        public final BigDecimal remainingPrincipal;
        public long paidCents;
        public LocalDate paidAt;
        public int daysPastDue;

        public Installment(int installmentNo, LocalDate dueDate, BigDecimal principal, BigDecimal interest,
                           BigDecimal remainingPrincipal) {
            this.installmentNo = installmentNo;
            this.dueDate = dueDate;
            this.principal = principal;
            this.interest = interest;
            this.remainingPrincipal = remainingPrincipal;
        }

        public long totalCents() {
            return toCents(principal.add(interest));
        }

        public boolean isPaid() {
            return paidAt != null;
        }
    }

    public static final class Loan {
        public long id;
        public final LoanType type;
        public final BigDecimal principal;
        public final BigDecimal monthlyRate;
        public final int termMonths;
        public final LocalDate disbursedAt;
        public final String loanNo;
        public LoanStatus status = LoanStatus.ACTIVE;
        public final List<Installment> installments = new ArrayList<>();

        public Loan(LoanType type, BigDecimal principal, BigDecimal monthlyRate, int termMonths,
                    LocalDate disbursedAt, String loanNo) {
            this.type = type;
            this.principal = principal;
            this.monthlyRate = monthlyRate;
            this.termMonths = termMonths;
            this.disbursedAt = disbursedAt;
            this.loanNo = loanNo;
        }
    }

    public static final class Statement {
        public long id;
        public final Account card;
        public final LocalDate statementDate;
        public final LocalDate dueDate;
        public final long balanceCents;
        public final long minimumPaymentCents;
        public long paidCents;
        public LocalDate paidAt;
        public int daysPastDue;

        public Statement(Account card, LocalDate statementDate, LocalDate dueDate, long balanceCents,
                         long minimumPaymentCents) {
            this.card = card;
            this.statementDate = statementDate;
            this.dueDate = dueDate;
            this.balanceCents = balanceCents;
            this.minimumPaymentCents = minimumPaymentCents;
        }
    }

    public static final class Transaction {
        public long id;
        public final Account account;
        public final MerchantRef merchant;
        public final long amountCents;
        public final Direction direction;
        public final Channel channel;
        public final int installmentCount;
        public final String description;
        public final OffsetDateTime occurredAt;
        public final Category category;
        /** Creation order within the customer, used as a stable tie-breaker when sorting by time. */
        public final int sequence;

        public Transaction(Account account, MerchantRef merchant, long amountCents, Direction direction,
                           Channel channel, int installmentCount, String description, OffsetDateTime occurredAt,
                           Category category, int sequence) {
            this.account = account;
            this.merchant = merchant;
            this.amountCents = amountCents;
            this.direction = direction;
            this.channel = channel;
            this.installmentCount = installmentCount;
            this.description = description;
            this.occurredAt = occurredAt;
            this.category = category;
            this.sequence = sequence;
        }
    }

    public record Event(CustomerEventType type, LocalDate date, Account account, Loan loan, Integer daysPastDue,
                        String details) {
    }

    public record MonthlyState(YearMonth month, double stress, double debtToIncome, double realIncomeLoss,
                               double cardUtilization, double incomeVolatility, double bufferMonths,
                               long incomeCents, double pMissPayment, double pChurn, int daysPastDue,
                               boolean unemployed, boolean churnIntent) {
    }

    public record Latent(double resilience, double churnPropensity, PayerType payerType, double spendingStyle,
                         double incomeVolatilityBase, double raisePassThrough, Integer salaryDay,
                         long baseIncomeCents, boolean hasCar, boolean renter) {
    }

    public record Outcome(boolean defaulted, LocalDate defaultDate, boolean churned, LocalDate churnDate,
                          int latePaymentCount, int unemploymentMonths, double maxStress, double meanStress) {
    }

    /** Everything generated for one customer. */
    public static final class CustomerHistory {
        public final int index;
        public final Segment segment;
        public final int age;
        public final String city;
        public final OccupationGroup occupation;
        public final LocalDate customerSince;
        public long latestIncomeCents;
        public CustomerStatus status = CustomerStatus.ACTIVE;
        public Latent latent;
        public Outcome outcome;
        public final List<Account> accounts = new ArrayList<>();
        public final List<Loan> loans = new ArrayList<>();
        public final List<Statement> statements = new ArrayList<>();
        public final List<Transaction> transactions = new ArrayList<>();
        public final List<Event> events = new ArrayList<>();
        public final List<MonthlyState> monthlyStates = new ArrayList<>();

        public CustomerHistory(int index, Segment segment, int age, String city, OccupationGroup occupation,
                               LocalDate customerSince) {
            this.index = index;
            this.segment = segment;
            this.age = age;
            this.city = city;
            this.occupation = occupation;
            this.customerSince = customerSince;
        }

        /** Customer ids are the 1-based customer index, independent of chunking. */
        public long customerId() {
            return index + 1L;
        }
    }
}
