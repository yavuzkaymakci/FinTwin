package com.fintwin.generator;

import com.fintwin.generator.catalog.MerchantCatalog;
import com.fintwin.generator.macro.MacroPath;
import com.fintwin.generator.simulation.CustomerSimulator;
import com.fintwin.generator.simulation.Sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Shared fixtures for generator unit tests (no database). */
public final class GeneratorFixtures {

    public static final YearMonth START = YearMonth.of(2024, 10);

    private GeneratorFixtures() {
    }

    /** A plausible macro path: ~2.5% monthly inflation, falling policy rate, steadily rising USD/TRY. */
    public static MacroPath macroPath() {
        List<MacroPath.MonthlyMacro> months = new ArrayList<>();
        YearMonth m = YearMonth.of(2024, 1);
        for (int i = 0; i < 36; i++, m = m.plusMonths(1)) {
            months.add(new MacroPath.MonthlyMacro(m, i % 12 == 0 ? 4.5 : 2.3, 50 - i * 0.5, 30 + i * 0.5));
        }
        return new MacroPath(months, START);
    }

    public static CustomerSimulator simulator(long seed, int months) {
        return new CustomerSimulator(macroPath(), MerchantCatalog.build(seed), START, months, seed);
    }

    /** SHA-256 over everything observable in a customer's simulated history. */
    public static String fingerprint(List<Sim.CustomerHistory> histories) {
        StringBuilder sb = new StringBuilder();
        for (Sim.CustomerHistory h : histories) {
            sb.append(h.index).append(h.segment).append(h.age).append(h.city).append(h.occupation)
                    .append(h.customerSince).append(h.latestIncomeCents).append(h.status).append('\n');
            for (Sim.Account a : h.accounts) {
                sb.append(a.type).append(a.currency).append(a.openedAt).append(a.closedAt).append(a.balanceCents)
                        .append(a.creditLimitCents).append('\n');
            }
            for (Sim.Loan l : h.loans) {
                sb.append(l.type).append(l.principal).append(l.monthlyRate).append(l.termMonths)
                        .append(l.disbursedAt).append(l.status).append('\n');
            }
            for (Sim.Statement s : h.statements) {
                sb.append(s.statementDate).append(s.balanceCents).append(s.minimumPaymentCents).append(s.paidCents)
                        .append(s.daysPastDue).append('\n');
            }
            for (Sim.Transaction t : h.transactions) {
                sb.append(t.account.type).append(t.amountCents).append(t.direction).append(t.channel)
                        .append(t.installmentCount).append(t.description).append(t.occurredAt).append(t.category)
                        .append(t.merchant == null ? "-" : t.merchant.id()).append('\n');
            }
            h.events.forEach(e -> sb.append(e.type()).append(e.date()).append(e.daysPastDue()).append('\n'));
            h.monthlyStates.forEach(s -> sb.append(s).append('\n'));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
