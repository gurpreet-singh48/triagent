package com.incidentintel.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for backend integration tests: real Postgres (Testcontainers,
 * Flyway runs the real migrations against it) and real Redis, so idempotency
 * and constraint behavior is tested against the actual infrastructure, not
 * mocks. The agent-service is stubbed with WireMock — a real classification
 * call would hit OpenAI, which is neither deterministic nor free.
 *
 * Containers are static (one per JVM, shared across all subclasses) so the
 * suite doesn't pay container-startup cost per test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("triagent")
            .withUsername("triagent")
            .withPassword("triagent");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    protected static final WireMockServer AGENT_SERVICE = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        AGENT_SERVICE.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("triagent.agent-service.url", () -> "http://localhost:" + AGENT_SERVICE.port());
        // Short timeouts so the agent-timeout test doesn't wait out a real
        // 30s production timeout; overridden per-test where a longer delay
        // is needed to prove the timeout itself fires.
        registry.add("triagent.agent-service.connect-timeout-ms", () -> "2000");
        registry.add("triagent.agent-service.read-timeout-ms", () -> "2000");
        // Never a real key; nothing in this test suite exercises chat/RAG,
        // only webhook/callback/ticket endpoints — Spring AI's OpenAI
        // autoconfiguration just needs a non-blank string to construct its
        // beans without making a network call at startup.
        registry.add("spring.ai.openai.api-key", () -> "test-key-not-real");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @BeforeEach
    void resetAgentServiceStub() {
        AGENT_SERVICE.resetAll();
    }
}
