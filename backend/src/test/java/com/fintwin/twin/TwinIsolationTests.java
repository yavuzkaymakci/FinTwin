package com.fintwin.twin;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** The twin is fed by Kafka events only: no core banking classes, tables or ground truth. */
class TwinIsolationTests {

    private static final Path TWIN_SOURCES = Path.of("src/main/java/com/fintwin/twin");
    /** Table references in SQL: FROM/JOIN/INTO/UPDATE followed by a name. */
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "\\b(?:FROM|JOIN|INTO|UPDATE|TRUNCATE TABLE)\\s+([a-z_][a-z_.]*)\\b(?!\\s*\\()");

    @Test
    void twinDoesNotDependOnCoreBankingOrGeneratorCode() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.fintwin");
        noClasses().that().resideInAPackage("com.fintwin.twin..")
                .should().dependOnClassesThat().resideInAnyPackage("com.fintwin.customer..",
                        "com.fintwin.account..", "com.fintwin.loan..", "com.fintwin.merchant..",
                        "com.fintwin.transaction..", "com.fintwin.macro..", "com.fintwin.generator..",
                        "com.fintwin.feed..")
                .check(classes);
    }

    @Test
    void twinSqlOnlyTouchesTheTwinSchema() throws IOException {
        List<String> violations;
        try (Stream<Path> files = Files.walk(TWIN_SOURCES)) {
            violations = files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    String source = Files.readString(p);
                    Stream.Builder<String> found = Stream.builder();
                    if (source.contains("ground_truth")) {
                        found.add(p + ": ground_truth");
                    }
                    Matcher m = TABLE_REFERENCE.matcher(source);
                    while (m.find()) {
                        String table = m.group(1);
                        if (!table.startsWith("twin.") && !table.equals("unnest")) {
                            found.add(p + ": " + table);
                        }
                    }
                    return found.build();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertThat(violations).isEmpty();
    }
}
