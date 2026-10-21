package com.fintwin.twin.consumer;

import com.fintwin.events.InvalidEventException;
import com.fintwin.events.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Batch listener container for the twin: manual batch acknowledgement after the database transaction commits,
 * exponential back-off retries, and dead-lettering to {@code <topic>.DLT} for records that still fail.
 */
@Configuration
public class TwinKafkaConfig {

    public static final String CONTAINER_FACTORY = "twinListenerContainerFactory";

    @Bean
    DefaultErrorHandler twinErrorHandler(KafkaTemplate<String, String> template, MeterRegistry registry,
                                         @Value("${fintwin.twin.consumer.retries:3}") int retries) {
        Counter deadLettered = Counter.builder("twin.events.dlt")
                .description("Records sent to a dead letter topic").register(registry);
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.deadLetter(record.topic()), record.partition()));
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            publisher.accept(record, ex);
            deadLettered.increment();
        };
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxAttempts(retries);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // A payload that cannot be parsed will never succeed: dead-letter it immediately.
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> twinListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, DefaultErrorHandler twinErrorHandler,
            @Value("${fintwin.twin.consumer.concurrency:6}") int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setMicrometerEnabled(true);
        factory.setCommonErrorHandler(twinErrorHandler);
        return factory;
    }
}
