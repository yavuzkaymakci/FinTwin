package com.fintwin.generator;

import com.fintwin.generator.report.GenerationReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Entry point of the {@code generate} profile: generate, report, exit. */
@Component
@Profile("generate")
class GeneratorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeneratorRunner.class);

    private final SyntheticDataGenerator generator;
    private final GenerationReportService reportService;
    private final GeneratorProperties properties;
    private final ApplicationContext context;

    GeneratorRunner(SyntheticDataGenerator generator, GenerationReportService reportService,
                    GeneratorProperties properties, ApplicationContext context) {
        this.generator = generator;
        this.reportService = reportService;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            GenerationResult result = generator.generate();
            long r0 = System.nanoTime();
            String report = reportService.buildReport(result);
            log.info("Report queries took {} ms", (System.nanoTime() - r0) / 1_000_000);
            if (properties.reportPath() != null && !properties.reportPath().isBlank()) {
                reportService.writeReport(Path.of(properties.reportPath()), report);
            } else {
                log.info("Generation report:\n{}", report);
            }
        } catch (RuntimeException e) {
            log.error("Data generation failed", e);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
