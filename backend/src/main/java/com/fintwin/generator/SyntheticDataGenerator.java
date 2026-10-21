package com.fintwin.generator;

import com.fintwin.generator.catalog.MerchantCatalog;
import com.fintwin.generator.macro.MacroPath;
import com.fintwin.generator.simulation.CustomerSimulator;
import com.fintwin.generator.simulation.Sim.CustomerHistory;
import com.fintwin.generator.writer.JdbcBatchWriter;
import com.fintwin.macro.domain.MacroIndicator;
import com.fintwin.macro.repository.MacroIndicatorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Generates the synthetic portfolio: loads the macro path, builds the merchant catalog, simulates customers in
 * parallel chunks and bulk-writes chunks on several connections while the next ones are being simulated.
 */
@Service
public class SyntheticDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataGenerator.class);

    private final GeneratorProperties properties;
    private final MacroIndicatorRepository macroRepository;
    private final JdbcBatchWriter writer;
    private final TransactionTemplate transactionTemplate;

    public SyntheticDataGenerator(GeneratorProperties properties, MacroIndicatorRepository macroRepository,
                                  JdbcBatchWriter writer, TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.macroRepository = macroRepository;
        this.writer = writer;
        this.transactionTemplate = transactionTemplate;
    }

    public GenerationResult generate() {
        return generate(properties);
    }

    public GenerationResult generate(GeneratorProperties props) {
        Instant startedAt = Instant.now();
        long t0 = System.nanoTime();
        log.info("Generating {} customers x {} months ({}..{}), seed={}", props.customerCount(), props.months(),
                props.startMonth(), props.endMonth(), props.seed());

        MacroPath macro = loadMacroPath(props.startMonth());
        if (props.reset()) {
            transactionTemplate.executeWithoutResult(s -> writer.truncateGeneratedTables());
            log.info("Truncated previously generated data");
        } else if (writer.countCustomers() > 0) {
            throw new IllegalStateException("Generated data already exists; run with fintwin.generator.reset=true");
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        MerchantCatalog catalog = MerchantCatalog.build(props.seed());
        transactionTemplate.executeWithoutResult(s ->
                counts.put("merchant", writer.writeMerchants(catalog.all(), props.batchSize())));

        CustomerSimulator simulator = new CustomerSimulator(macro, catalog, props.startMonth(), props.months(),
                props.seed());
        JdbcBatchWriter.IdSequence ids = new JdbcBatchWriter.IdSequence();
        long simulationNanos = 0;
        AtomicLong writeNanos = new AtomicLong();
        AtomicLong writtenCustomers = new AtomicLong();
        ForkJoinPool pool = new ForkJoinPool(props.effectiveParallelism());
        ExecutorService writers = Executors.newFixedThreadPool(props.writeThreads());
        // Bounds the number of simulated chunks held in memory while waiting to be written.
        Semaphore inFlight = new Semaphore(props.writeThreads() + 1);
        List<Future<Map<String, Long>>> writes = new ArrayList<>();
        try {
            List<int[]> chunks = new ArrayList<>();
            for (int from = 0; from < props.customerCount(); from += props.chunkSize()) {
                chunks.add(new int[]{from, Math.min(from + props.chunkSize(), props.customerCount())});
            }
            CompletableFuture<Simulated> next = simulateAsync(simulator, chunks.getFirst(), pool);
            for (int c = 0; c < chunks.size(); c++) {
                Simulated simulated = next.join();
                simulationNanos += simulated.nanos();
                if (c + 1 < chunks.size()) {
                    next = simulateAsync(simulator, chunks.get(c + 1), pool);
                }
                // Ids are assigned sequentially in chunk order (deterministic); writing may then run in parallel.
                JdbcBatchWriter.PreparedChunk prepared = writer.assignIds(simulated.histories(), ids);
                inFlight.acquireUninterruptibly();
                writes.add(writers.submit(() -> {
                    try {
                        long w0 = System.nanoTime();
                        Map<String, Long> chunkCounts = transactionTemplate.execute(s ->
                                writer.writeChunk(prepared, props.batchSize()));
                        writeNanos.addAndGet(System.nanoTime() - w0);
                        long done = writtenCustomers.addAndGet(prepared.histories().size());
                        if (done % (props.chunkSize() * 4L) == 0 || done == props.customerCount()) {
                            log.info("Progress: {}/{} customers written, {} s elapsed", done,
                                    props.customerCount(), String.format("%.1f", (System.nanoTime() - t0) / 1e9));
                        }
                        return chunkCounts;
                    } finally {
                        inFlight.release();
                    }
                }));
            }
            for (Future<Map<String, Long>> write : writes) {
                Map<String, Long> chunkCounts = write.get();
                if (chunkCounts != null) {
                    chunkCounts.forEach((table, n) -> counts.merge(table, n, Long::sum));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Generation interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Writing a chunk failed", e.getCause());
        } finally {
            pool.shutdown();
            writers.shutdownNow();
        }

        long f0 = System.nanoTime();
        writer.finish();
        writeNanos.addAndGet(System.nanoTime() - f0);
        Duration total = Duration.ofNanos(System.nanoTime() - t0);
        writer.recordRun(props.seed(), props.customerCount(), props.months(), props.endMonth().atDay(1), startedAt,
                total.toMillis(), counts.toString());

        long rows = counts.values().stream().mapToLong(Long::longValue).sum();
        log.info("Generation finished in {} s: {} rows ({} rows/s); simulation {} s, writing {} s",
                String.format("%.1f", total.toMillis() / 1000.0), rows,
                Math.round(rows / Math.max(total.toMillis() / 1000.0, 0.001)),
                String.format("%.1f", simulationNanos / 1e9), String.format("%.1f", writeNanos.get() / 1e9));
        counts.forEach((table, n) -> log.info("  {} = {}", table, n));
        return new GenerationResult(props, counts, total, Duration.ofNanos(simulationNanos),
                Duration.ofNanos(writeNanos.get()));
    }

    private record Simulated(List<CustomerHistory> histories, long nanos) {
    }

    private static CompletableFuture<Simulated> simulateAsync(CustomerSimulator simulator, int[] range,
                                                              ForkJoinPool pool) {
        return CompletableFuture.supplyAsync(() -> {
            long s0 = System.nanoTime();
            // Parallel stream inside the pool; toList() keeps encounter (customer index) order.
            List<CustomerHistory> histories = IntStream.range(range[0], range[1]).parallel()
                    .mapToObj(simulator::simulate).toList();
            return new Simulated(histories, System.nanoTime() - s0);
        }, pool);
    }

    MacroPath loadMacroPath(YearMonth baseMonth) {
        Map<YearMonth, double[]> byMonth = new TreeMap<>();
        for (MacroIndicator m : macroRepository.findAllByOrderByPeriodAscIndicatorAsc()) {
            double[] values = byMonth.computeIfAbsent(YearMonth.from(m.getPeriod()),
                    k -> new double[]{Double.NaN, Double.NaN, Double.NaN});
            values[m.getIndicator().ordinal()] = m.getValue().doubleValue();
        }
        List<MacroPath.MonthlyMacro> months = new ArrayList<>();
        byMonth.forEach((month, v) -> {
            if (!Double.isNaN(v[0]) && !Double.isNaN(v[1]) && !Double.isNaN(v[2])) {
                months.add(new MacroPath.MonthlyMacro(month, v[0], v[1], v[2]));
            }
        });
        if (months.isEmpty()) {
            throw new IllegalStateException("No complete macro indicator months found");
        }
        return new MacroPath(months, baseMonth);
    }
}
