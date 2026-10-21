package com.fintwin.feed.publish;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.events.EventCodec;
import com.fintwin.events.Topics;
import com.fintwin.feed.FeedProperties;
import com.fintwin.feed.dto.PrepareResult;
import com.fintwin.feed.dto.ReplayStatus;
import com.fintwin.feed.outbox.OutboxRepository;
import com.fintwin.feed.outbox.OutboxRow;
import com.fintwin.feed.outbox.ReplayStateRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replays the core banking outbox to Kafka, either as fast as possible up to a date (bulk) or paced by a simulated
 * clock (live). Runs on a single background thread; the cursor is persisted after every page so a replay can be
 * stopped and resumed.
 */
@Service
public class ReplayService {

    public enum Mode {
        IDLE, PREPARING, BULK, LIVE
    }

    static final ZoneId BANK_ZONE = ZoneId.of("Europe/Istanbul");
    /** Upper bound for "publish everything" that still fits a SQL timestamp. */
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final OutboxRepository outbox;
    private final EventAssembler assembler;
    private final KafkaTemplate<String, String> kafka;
    private final FeedProperties properties;
    private final Counter publishedCounter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "core-banking-feed");
        t.setDaemon(true);
        return t;
    });

    private volatile Mode mode = Mode.IDLE;
    private volatile boolean stopRequested;
    private volatile ReplayClock clock;
    private volatile Instant lastPublishedAt;
    private volatile Instant sessionStartedAt;
    private volatile long sessionStartNanos;
    private volatile long sessionEndNanos;
    private volatile String lastError;
    private final AtomicLong sessionPublished = new AtomicLong();
    private final AtomicLong position = new AtomicLong();

    public ReplayService(OutboxRepository outbox, EventAssembler assembler, KafkaTemplate<String, String> kafka,
                         FeedProperties properties, MeterRegistry registry) {
        this.outbox = outbox;
        this.assembler = assembler;
        this.kafka = kafka;
        this.properties = properties;
        this.publishedCounter = Counter.builder("feed.events.published")
                .description("Events published by the core banking feed").register(registry);
        Gauge.builder("feed.replay.simulated.time", this,
                        s -> s.simulatedTime() == null ? 0 : s.simulatedTime().getEpochSecond())
                .description("Simulated time of the replay (epoch seconds)").register(registry);
        Gauge.builder("feed.replay.position", position, AtomicLong::get)
                .description("Last published outbox position").register(registry);
    }

    /** Builds the outbox from the core banking tables (synchronously). */
    public synchronized PrepareResult prepare() {
        requireIdle();
        mode = Mode.PREPARING;
        try {
            long t0 = System.nanoTime();
            LocalDate start = outbox.feedStartDate();
            if (start == null) {
                throw new IllegalStateException("No core banking data; run the generator first");
            }
            String epoch = outbox.datasetFingerprint();
            long total = outbox.rebuild(start);
            outbox.analyze();
            Instant[] range = outbox.range();
            outbox.savePrepared(epoch, range[0], range[1], total);
            position.set(0);
            lastPublishedAt = null;
            long millis = (System.nanoTime() - t0) / 1_000_000;
            log.info("Outbox prepared: {} events ({} .. {}) in {} ms, epoch {}", total, range[0], range[1], millis,
                    epoch);
            return new PrepareResult(epoch, total, range[0], range[1], millis);
        } finally {
            mode = Mode.IDLE;
        }
    }

    /** Publishes everything up to the end of {@code until} (local date; null = all) as fast as possible. */
    public synchronized ReplayStatus startBulk(LocalDate until) {
        Instant limit = until == null ? END_OF_TIME : until.plusDays(1).atStartOfDay(BANK_ZONE).toInstant();
        beginSession(Mode.BULK);
        executor.submit(() -> run(() -> publishUntil(() -> limit, false)));
        return status();
    }

    /**
     * Publishes in simulated real time. If {@code from} is after the cursor, events before it are first published
     * at full speed (fast-forward).
     */
    public synchronized ReplayStatus startLive(Double secondsPerDay, LocalDate from) {
        double speed = secondsPerDay == null ? properties.defaultSecondsPerDay() : secondsPerDay;
        ReplayStateRow state = requirePrepared();
        Instant next = outbox.occurredAtOfNext(state.cursorPosition());
        Instant start = from != null ? from.atStartOfDay(BANK_ZONE).toInstant()
                : next != null ? next : state.feedEnd();
        beginSession(Mode.LIVE);
        executor.submit(() -> run(() -> {
            publishUntil(() -> start, false);
            clock = new ReplayClock(start, speed, System::nanoTime);
            publishUntil(() -> clock.now(), true);
        }));
        return status();
    }

    public ReplayStatus stop() {
        stopRequested = true;
        return status();
    }

    public synchronized ReplayStatus changeSpeed(double secondsPerDay) {
        ReplayClock current = clock;
        if (mode != Mode.LIVE || current == null) {
            throw new IllegalStateException("Speed can only be changed during a live replay");
        }
        current.setSecondsPerDay(secondsPerDay);
        return status();
    }

    /** Moves the cursor back to the beginning (events will be republished; consumers deduplicate by id). */
    public synchronized ReplayStatus reset() {
        requireIdle();
        outbox.resetCursor();
        position.set(0);
        lastPublishedAt = null;
        return status();
    }

    public ReplayStatus status() {
        ReplayStateRow state = outbox.state();
        long published = sessionPublished.get();
        long end = mode == Mode.BULK || mode == Mode.LIVE ? System.nanoTime() : sessionEndNanos;
        double seconds = sessionStartedAt == null ? 0 : Math.max(end - sessionStartNanos, 1) / 1e9;
        ReplayClock c = clock;
        return new ReplayStatus(mode.name(), state.datasetEpoch(), state.feedStart(), state.feedEnd(),
                simulatedTime(), mode == Mode.LIVE && c != null ? c.secondsPerDay() : null, state.cursorPosition(),
                state.totalEvents(), state.publishedTotal(), published, seconds == 0 ? 0 : published / seconds,
                sessionStartedAt, lastError);
    }

    public Mode mode() {
        return mode;
    }

    @PreDestroy
    void shutdown() {
        stopRequested = true;
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- internals

    private Instant simulatedTime() {
        ReplayClock c = clock;
        return mode == Mode.LIVE && c != null ? c.now() : lastPublishedAt;
    }

    private void beginSession(Mode newMode) {
        requireIdle();
        ReplayStateRow state = requirePrepared();
        assembler.configure(state.datasetEpoch(),
                state.feedStart().atZone(BANK_ZONE).toLocalDate());
        position.set(state.cursorPosition());
        lastPublishedAt = state.cursorOccurredAt();
        stopRequested = false;
        lastError = null;
        clock = null;
        sessionPublished.set(0);
        sessionStartedAt = Instant.now();
        sessionStartNanos = System.nanoTime();
        mode = newMode;
    }

    private void run(Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            log.error("Replay failed", e);
        } finally {
            sessionEndNanos = System.nanoTime();
            log.info("Replay {} finished: {} events in {} s", mode, sessionPublished.get(),
                    String.format("%.1f", (sessionEndNanos - sessionStartNanos) / 1e9));
            mode = Mode.IDLE;
        }
    }

    private interface InstantSupplier {
        Instant get();
    }

    /**
     * Publishes pages with {@code occurred_at < limit}. With {@code paced}, keeps polling the moving limit until the
     * outbox is exhausted or a stop is requested; otherwise returns when no row below the limit remains.
     */
    private void publishUntil(InstantSupplier limit, boolean paced) {
        while (!stopRequested) {
            List<OutboxRow> page = outbox.page(position.get(), limit.get(), properties.pageSize());
            if (page.isEmpty()) {
                if (!paced || outbox.occurredAtOfNext(position.get()) == null) {
                    return;
                }
                sleep(properties.liveTickMillis());
                continue;
            }
            publish(page);
        }
    }

    private void publish(List<OutboxRow> page) {
        List<CoreBankingEvent> events = assembler.assemble(page);
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(events.size());
        for (CoreBankingEvent event : events) {
            sends.add(kafka.send(Topics.topicOf(event), Long.toString(event.customerId()), EventCodec.encode(event)));
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).join();
        OutboxRow last = page.getLast();
        outbox.saveCursor(last.position(), last.occurredAt(), page.size());
        position.set(last.position());
        lastPublishedAt = last.occurredAt();
        sessionPublished.addAndGet(page.size());
        publishedCounter.increment(page.size());
    }

    private ReplayStateRow requirePrepared() {
        ReplayStateRow state = outbox.state();
        if (!state.prepared()) {
            throw new IllegalStateException("Outbox not prepared; call POST /api/admin/replay/prepare first");
        }
        return state;
    }

    private void requireIdle() {
        if (mode != Mode.IDLE) {
            throw new IllegalStateException("Replay is busy (" + mode + ")");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
