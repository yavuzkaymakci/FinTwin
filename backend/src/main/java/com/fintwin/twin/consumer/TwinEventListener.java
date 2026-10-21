package com.fintwin.twin.consumer;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.events.EventCodec;
import com.fintwin.events.InvalidEventException;
import com.fintwin.events.Topics;
import com.fintwin.twin.service.TwinProjectionService;
import com.fintwin.twin.store.StoredEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumes all core banking topics in batches. Records are decoded up to the first invalid one; the valid prefix is
 * processed (and committed) and the invalid record is reported by index so the error handler dead-letters exactly
 * that record and redelivers the rest.
 */
@Component
public class TwinEventListener {

    public static final String LISTENER_ID = "twin-projector";

    private final TwinProjectionService projection;
    private final Timer batchTimer;
    private final Timer consumerLag;

    public TwinEventListener(TwinProjectionService projection, MeterRegistry registry) {
        this.projection = projection;
        this.batchTimer = Timer.builder("twin.batch.duration").description("Time to process one consumer batch")
                .register(registry);
        this.consumerLag = Timer.builder("twin.consumer.lag")
                .description("Delay between publication (record timestamp) and processing").register(registry);
    }

    @KafkaListener(id = LISTENER_ID, groupId = "${fintwin.twin.consumer.group-id:fintwin-twin}",
            topics = {Topics.CUSTOMERS_REGISTERED, Topics.ACCOUNTS_OPENED, Topics.ACCOUNTS_CLOSED,
                    Topics.TRANSACTIONS_RECORDED, Topics.CARD_STATEMENTS_ISSUED, Topics.LOANS_DISBURSED,
                    Topics.LOAN_INSTALLMENTS_SETTLED, Topics.PAYMENTS_PAST_DUE},
            containerFactory = TwinKafkaConfig.CONTAINER_FACTORY,
            autoStartup = "${fintwin.twin.consumer.enabled:true}")
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        long start = System.nanoTime();
        List<StoredEvent> events = new ArrayList<>(records.size());
        InvalidEventException invalid = null;
        int invalidIndex = -1;
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, String> r = records.get(i);
            try {
                CoreBankingEvent event = EventCodec.decode(r.topic(), r.value());
                events.add(new StoredEvent(event, r.topic(), r.partition(), r.offset(), r.value()));
            } catch (InvalidEventException e) {
                invalid = e;
                invalidIndex = i;
                break;
            }
        }
        if (!events.isEmpty()) {
            projection.process(events);
            ConsumerRecord<String, String> last = records.get(events.size() - 1);
            consumerLag.record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - last.timestamp())));
        }
        batchTimer.record(Duration.ofNanos(System.nanoTime() - start));
        if (invalid != null) {
            throw new BatchListenerFailedException(invalid.getMessage(), invalid, invalidIndex);
        }
    }
}
