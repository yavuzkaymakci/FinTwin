package com.fintwin.ml;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wires the ML client with its own timeouts and the Resilience4j instances configured in application.yml. */
@Configuration
@EnableConfigurationProperties(MlServiceProperties.class)
class MlClientConfig {

    @Bean
    MlServiceClient mlServiceClient(RestClient.Builder builder, MlServiceProperties properties,
                                    RetryRegistry retries, CircuitBreakerRegistry circuitBreakers) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient rest = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        return new MlServiceClient(rest, retries.retry(MlServiceClient.RESILIENCE_NAME),
                circuitBreakers.circuitBreaker(MlServiceClient.RESILIENCE_NAME));
    }
}
