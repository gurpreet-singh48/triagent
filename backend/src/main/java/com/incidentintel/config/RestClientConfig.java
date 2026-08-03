package com.incidentintel.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.time.Duration;

/**
 * A RestClientCustomizer applies on top of Spring Boot's autoconfigured
 * RestClient.Builder — unlike defining a fresh RestClient.Builder @Bean,
 * this preserves the autoconfigured HttpMessageConverters, which use the
 * application's Jackson ObjectMapper (snake_case naming strategy, see
 * application.yml). Building a bare RestClient.builder() here instead
 * would silently serialize requests in camelCase.
 *
 * Timeouts are configurable (triagent.agent-service.{connect,read}-timeout-ms)
 * specifically so tests can exercise the timeout path in milliseconds
 * instead of waiting out the real 30s production default.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer agentServiceTimeoutCustomizer(TriagentProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(properties.agentService().connectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.agentService().readTimeoutMs()));
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(settings);
        return builder -> builder.requestFactory(factory);
    }
}
