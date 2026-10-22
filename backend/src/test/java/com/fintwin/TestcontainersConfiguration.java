package com.fintwin;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL and Kafka containers for integration tests. Images match infra/docker-compose.yml.
 * <p>
 * The containers are JVM-wide singletons and deliberately not Spring beans: container beans are stopped when their
 * application context closes, which would break the other cached test contexts still using them. They are wired in
 * through connection properties instead and removed by Testcontainers (Ryuk) when the JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final long MIB = 1024L * 1024;

    // Memory caps keep the test run within a laptop budget (JVM 1 GB + containers ~1.5 GB).
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withCommand("postgres", "-c", "shared_buffers=128MB", "-c", "fsync=off")
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768 * MIB));

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.1"))
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768 * MIB));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Bean
    DynamicPropertyRegistrar containerProperties() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        };
    }
}
