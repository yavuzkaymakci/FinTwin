package com.fintwin.generator;

import com.fintwin.TestcontainersConfiguration;
import com.fintwin.generator.report.GenerationReportService;
import com.fintwin.generator.writer.JdbcBatchWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end generation into a PostgreSQL Testcontainer with the real Flyway schema. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SyntheticDataGeneratorIntegrationTests {

    private static final String CHECKSUM = """
            SELECT md5(string_agg(t.id || ':' || t.account_id || ':' || t.amount || ':' || t.raw_description || ':'
                                  || t.occurred_at || ':' || l.category_code, ',' ORDER BY t.id))
            FROM bank_transaction t JOIN ground_truth.transaction_label l ON l.transaction_id = t.id""";

    @Autowired
    private SyntheticDataGenerator generator;

    @Autowired
    private GenerationReportService reportService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcBatchWriter writer;

    /** The container is shared with other test classes; leave no generated rows behind. */
    @AfterEach
    void cleanUp() {
        writer.truncateGeneratedTables();
    }

    private static GeneratorProperties props(long seed) {
        return new GeneratorProperties(80, 6, seed, YearMonth.of(2025, 6), true, 25, 1000, 2, 3, "");
    }

    @Test
    void generatesConsistentDataAndIsReproducible() {
        GenerationResult first = generator.generate(props(42));
        String firstChecksum = jdbc.queryForObject(CHECKSUM, String.class);

        assertThat(count("customer")).isEqualTo(80);
        assertThat(count("bank_transaction")).isEqualTo(first.rowCounts().get("bank_transaction")).isGreaterThan(5_000);
        assertThat(count("ground_truth.transaction_label")).isEqualTo(count("bank_transaction"));
        assertThat(count("ground_truth.customer_outcome")).isEqualTo(80);
        assertThat(count("ground_truth.customer_monthly_state")).isPositive();
        assertThat(count("merchant")).isPositive();
        // Installment plans: principal parts sum to the loan principal for every loan.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM loan l
                WHERE l.principal <> (SELECT SUM(i.principal_amount) FROM loan_installment i WHERE i.loan_id = l.id)""",
                Long.class)).isZero();
        // Sequences were moved past the explicit ids, so application inserts keep working.
        Long nextId = jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('customer', 'id'))", Long.class);
        assertThat(nextId).isEqualTo(81L);

        generator.generate(props(42));
        assertThat(jdbc.queryForObject(CHECKSUM, String.class)).isEqualTo(firstChecksum);
        assertThat(count("customer")).isEqualTo(80);

        generator.generate(props(7));
        assertThat(jdbc.queryForObject(CHECKSUM, String.class)).isNotEqualTo(firstChecksum);
    }

    @Test
    void writesReportIntoMarkedSection(@TempDir Path dir) throws Exception {
        GenerationResult result = generator.generate(props(11));
        Path doc = dir.resolve("data-generation.md");
        Files.writeString(doc, "# Data generation\n\nDesign notes.\n\n" + GenerationReportService.START_MARKER
                + "\nold\n" + GenerationReportService.END_MARKER + "\n\nTrailer.\n");

        reportService.writeReport(doc, reportService.buildReport(result));

        String content = Files.readString(doc);
        assertThat(content).startsWith("# Data generation\n\nDesign notes.").endsWith("Trailer.\n")
                .doesNotContain("\nold\n")
                .contains("### Row counts", "### Default and churn by financial stress decile",
                        "### Category distribution", "| `GROCERY` |");
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
