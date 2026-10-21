package com.fintwin.config;

import com.fintwin.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares the core banking feed topics and their dead letter topics (broker auto-creation is disabled).
 * All topics share the partition count so that a customer's events land on the same partition index everywhere.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    KafkaAdmin.NewTopics coreBankingTopics(@Value("${fintwin.kafka.partitions:6}") int partitions,
                                           @Value("${fintwin.kafka.replication-factor:1}") short replicas) {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : Topics.ALL) {
            topics.add(TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build());
            topics.add(TopicBuilder.name(Topics.deadLetter(topic)).partitions(partitions).replicas(replicas).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
