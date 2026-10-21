package com.fintwin;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class KafkaContainerSmokeTests {

    private static final String TOPIC = "fintwin.test.smoke.v1";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // Connection details come from the container; KafkaProperties would still point at the local compose broker.
    @Autowired
    private KafkaConnectionDetails connectionDetails;

    @Test
    void producedMessageCanBeConsumed() throws Exception {
        String bootstrapServers = String.join(",", connectionDetails.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }

        kafkaTemplate.send(TOPIC, "key", "hello").get(30, TimeUnit.SECONDS);

        Map<String, Object> consumerProps = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "smoke-test",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            List<String> values = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (values.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> values.add(r.value()));
            }
            assertThat(values).containsExactly("hello");
        }
    }
}
