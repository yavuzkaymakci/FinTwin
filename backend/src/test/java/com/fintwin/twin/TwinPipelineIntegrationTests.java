package com.fintwin.twin;

import com.fintwin.TestcontainersConfiguration;
import com.fintwin.events.CoreBankingEvent;
import com.fintwin.events.Topics;
import com.fintwin.feed.consistency.ConsistencyCheckService;
import com.fintwin.feed.dto.ConsistencyReport;
import com.fintwin.feed.outbox.OutboxRepository;
import com.fintwin.feed.publish.ReplayService;
import com.fintwin.generator.GeneratorProperties;
import com.fintwin.generator.SyntheticDataGenerator;
import com.fintwin.generator.writer.JdbcBatchWriter;
import com.fintwin.twin.dto.FeatureHistory;
import com.fintwin.twin.dto.RebuildResult;
import com.fintwin.twin.dto.TwinView;
import com.fintwin.twin.projection.TwinFeatureCalculator;
import com.fintwin.twin.projection.TwinFeatures;
import com.fintwin.twin.projection.TwinProjector;
import com.fintwin.twin.service.TwinAdminService;
import com.fintwin.twin.service.TwinQueryService;
import com.fintwin.twin.service.TwinRebuildService;
import com.fintwin.twin.store.EventStoreRepository;
import com.fintwin.twin.store.TwinStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End to end over real Kafka and PostgreSQL containers: generate core banking data, prepare the outbox, replay it,
 * and check the twin. One ordered class because all steps share the topics and the consumer group.
 */
@SpringBootTest(properties = "fintwin.twin.consumer.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TwinPipelineIntegrationTests {

    private static final String STATE_CHECKSUM = """
            SELECT md5(string_agg(customer_id || ':' || last_seq || ':' || state::text || ':' || features::text, ','
                                  ORDER BY customer_id)) FROM twin.customer_twin_state""";

    @Autowired
    private SyntheticDataGenerator generator;
    @Autowired
    private JdbcBatchWriter generatedData;
    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private ReplayService replay;
    @Autowired
    private TwinAdminService twinAdmin;
    @Autowired
    private TwinQueryService queries;
    @Autowired
    private TwinRebuildService rebuild;
    @Autowired
    private EventStoreRepository eventStore;
    @Autowired
    private TwinStateRepository states;
    @Autowired
    private ConsistencyCheckService consistency;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private KafkaConnectionDetails kafkaConnection;
    @Autowired
    private MeterRegistry metrics;
    @Autowired
    private JdbcTemplate jdbc;

    private long totalEvents;

    @AfterAll
    void cleanUp() {
        twinAdmin.reset();
        generatedData.truncateGeneratedTables();
        outbox.invalidate();
    }

    @Test
    @Order(1)
    void bulkReplayBuildsTwinsWhoseBalancesMatchCoreBanking() {
        generator.generate(new GeneratorProperties(40, 4, 99, YearMonth.of(2025, 9), true, 20, 1000, 2, 2, ""));
        twinAdmin.reset();
        totalEvents = replay.prepare().totalEvents();
        assertThat(totalEvents).isGreaterThan(5_000);

        replay.startBulk(null);
        awaitReplayIdle();
        awaitTwinCaughtUp();

        assertThat(replay.status().position()).isEqualTo(totalEvents);
        assertThat(eventStore.count()).isEqualTo(totalEvents);
        ConsistencyReport report = consistency.check(5);
        assertThat(report.replayComplete()).isTrue();
        assertThat(report.mismatchedAccounts()).as("%s", report.firstMismatches()).isZero();
        assertThat(report.accounts()).isPositive();
        assertThat(report.sample()).isNotEmpty().allMatch(ConsistencyReport.CustomerComparison::match);

        TwinView twin = queries.current(1);
        assertThat(twin.features().spend90d()).isPositive();
        assertThat(twin.lastSequence()).isPositive();
        assertThat(states.countSnapshots()).isPositive();
    }

    @Test
    @Order(2)
    void duplicatesAreIgnored() {
        String before = jdbc.queryForObject(STATE_CHECKSUM, String.class);
        double duplicatesBefore = metrics.counter("twin.events.duplicates").count();
        // The same event twice, directly on Kafka.
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT topic, customer_id, payload::text AS payload FROM twin.event_store
                WHERE event_type = 'TransactionRecorded' ORDER BY customer_id, customer_seq LIMIT 1""");
        for (int i = 0; i < 2; i++) {
            kafka.send((String) stored.get("topic"), stored.get("customer_id").toString(),
                    (String) stored.get("payload")).join();
        }
        // And the whole feed a second time.
        replay.reset();
        replay.startBulk(null);
        awaitReplayIdle();
        await().atMost(Duration.ofMinutes(2)).until(() ->
                metrics.counter("twin.events.duplicates").count() >= duplicatesBefore + totalEvents + 2);

        assertThat(eventStore.count()).isEqualTo(totalEvents);
        assertThat(jdbc.queryForObject(STATE_CHECKSUM, String.class)).isEqualTo(before);
    }

    @Test
    @Order(3)
    void rebuildFromTheEventStoreReproducesTheLiveProjection() {
        String live = jdbc.queryForObject(STATE_CHECKSUM, String.class);
        String liveSnapshots = snapshotChecksum();

        RebuildResult verify = rebuild.run(TwinRebuildService.Mode.VERIFY);
        assertThat(verify.events()).isEqualTo(totalEvents);
        assertThat(verify.stateMismatches()).isZero();
        assertThat(verify.snapshotMismatches()).isZero();

        RebuildResult replace = rebuild.run(TwinRebuildService.Mode.REPLACE);
        assertThat(replace.customers()).isEqualTo(40);
        assertThat(jdbc.queryForObject(STATE_CHECKSUM, String.class)).isEqualTo(live);
        assertThat(snapshotChecksum()).isEqualTo(liveSnapshots);
    }

    @Test
    @Order(4)
    void asOfEqualsAFoldOfAllEventsBeforeTheCutoff() {
        List<LocalDate> dates = List.of(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 7, 17), LocalDate.of(2025, 8, 31),
                LocalDate.of(2025, 9, 30));
        Map<Long, List<CoreBankingEvent>> all = eventStore.eventsOf(List.of(1L, 7L, 23L, 40L));
        for (Map.Entry<Long, List<CoreBankingEvent>> customer : all.entrySet()) {
            for (LocalDate asOf : dates) {
                Instant cutoff = asOf.plusDays(1).atStartOfDay(TwinProjector.BANK_ZONE).toInstant();
                List<CoreBankingEvent> before = customer.getValue().stream()
                        .filter(e -> e.occurredAt().isBefore(cutoff)).toList();
                TwinRebuildService.Rebuilt expected = TwinRebuildService.rebuild(customer.getKey(), before);
                if (expected.events() == 0) {
                    continue;
                }
                TwinView actual = queries.asOf(customer.getKey(), asOf);
                TwinFeatures expectedFeatures = TwinFeatureCalculator.compute(expected.state(), cutoff);
                assertThat(actual.lastSequence()).isEqualTo(expected.state().lastSequence);
                assertThat(actual.features()).as("customer %d as of %s", customer.getKey(), asOf)
                        .isEqualTo(expectedFeatures);
            }
        }
        // The end of the horizon equals the current projection's balances.
        TwinView end = queries.asOf(1, LocalDate.of(2025, 9, 30));
        TwinView current = queries.current(1);
        assertThat(end.accounts()).isEqualTo(current.accounts());
        FeatureHistory history = queries.history(1, "utilizationRate");
        assertThat(history.points()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @Order(5)
    void malformedEventsGoToTheDeadLetterTopic() {
        double dltBefore = metrics.counter("twin.events.dlt").count();
        long eventsBefore = eventStore.count();
        kafka.send(Topics.TRANSACTIONS_RECORDED, "1", "{\"this is\": not json").join();

        await().atMost(Duration.ofSeconds(60)).until(() -> metrics.counter("twin.events.dlt").count() > dltBefore);
        List<ConsumerRecord<String, String>> dead = readTopic(Topics.deadLetter(Topics.TRANSACTIONS_RECORDED));
        assertThat(dead).anyMatch(r -> r.value().contains("not json"));
        assertThat(eventStore.count()).isEqualTo(eventsBefore);
    }

    @Test
    @Order(6)
    void staleVersionsAreRejected() {
        var current = states.find(1).orElseThrow();
        var write = new TwinStateRepository.StateWrite(current.state(),
                TwinFeatureCalculator.compute(current.state(), current.state().lastEventAt), current.version() - 1);
        assertThatThrownBy(() -> states.save(List.of(write))).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @Order(7)
    void liveReplayFollowsTheSimulatedClock() {
        replay.reset();
        // 1 simulated day per 50 ms: the 4-month horizon takes ~6 s.
        replay.startLive(0.05, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> replay.status().position() > 0);
        Instant early = replay.status().simulatedTime();
        long earlyPosition = replay.status().position();
        assertThat(replay.status().mode()).isEqualTo("LIVE");
        assertThat(earlyPosition).isLessThan(totalEvents);

        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(10))
                .until(() -> replay.status().simulatedTime().isAfter(early.plus(Duration.ofDays(5))));
        replay.changeSpeed(0.01);
        awaitReplayIdle();
        assertThat(replay.status().position()).isEqualTo(totalEvents);
        assertThat(replay.status().lastError()).isNull();
    }

    private void awaitReplayIdle() {
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(200))
                .until(() -> replay.mode() == ReplayService.Mode.IDLE);
    }

    private void awaitTwinCaughtUp() {
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(500)).until(() -> {
            Long unapplied = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM twin.event_store e
                    LEFT JOIN twin.customer_twin_state s ON s.customer_id = e.customer_id
                    WHERE e.customer_seq > COALESCE(s.last_seq, 0)""", Long.class);
            return eventStore.count() == totalEvents && unapplied != null && unapplied == 0;
        });
    }

    private String snapshotChecksum() {
        return jdbc.queryForObject("""
                SELECT md5(string_agg(customer_id || ':' || as_of || ':' || last_seq || ':' || state::text || ':'
                                      || features::text, ',' ORDER BY customer_id, as_of))
                FROM twin.customer_twin_snapshot""", String.class);
    }

    private List<ConsumerRecord<String, String>> readTopic(String topic) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (records.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }
}
