package com.incidentintel.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentintel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency guarantee (PLAN.md / reviewer spec): two requests with the
 * same dedup_key must produce exactly one incident, one agent invocation,
 * and one ticket — including when the two requests race concurrently.
 */
class WebhookIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private String webhookJson(String dedupKey, String summary) {
        return """
                {
                  "routing_key": "R123", "event_action": "trigger", "dedup_key": "%s",
                  "payload": {
                    "summary": "%s", "source": "payment-service-prod-1", "severity": "critical",
                    "timestamp": "2026-01-01T00:00:00Z", "component": "payment-service",
                    "group": "payments", "class": "5xx-spike", "custom_details": {"error_rate": "0.12"}
                  },
                  "client": "test", "client_url": "http://localhost"
                }
                """.formatted(dedupKey, summary);
    }

    private void stubAgentServiceSuccess() {
        stubFor(post(urlEqualTo("/triage")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":"triaged","ticket_id":null,"decision":"AUTO_TICKET","confidence":0.95,
                         "category":"5xx-spike","predicted_team":"payment-service"}
                        """)));
    }

    private ResponseEntity<String> postWebhook(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/webhooks/pagerduty", new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void sequentialDuplicate_returnsSameIncidentWithoutCallingAgentTwice() throws Exception {
        stubAgentServiceSuccess();
        String key = "seq-dup-" + System.nanoTime();

        ResponseEntity<String> first = postWebhook(webhookJson(key, "payment-service 5xx spike"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode firstBody = objectMapper.readTree(first.getBody());
        assertThat(firstBody.get("status").asText()).isEqualTo("processing");
        String incidentId = firstBody.get("incident_id").asText();

        ResponseEntity<String> second = postWebhook(webhookJson(key, "payment-service 5xx spike"));
        JsonNode secondBody = objectMapper.readTree(second.getBody());
        assertThat(secondBody.get("status").asText()).isEqualTo("duplicate");
        assertThat(secondBody.get("incident_id").asText()).isEqualTo(incidentId);

        AGENT_SERVICE.verify(1, postRequestedFor(urlEqualTo("/triage")));
    }

    @Test
    void concurrentDuplicates_produceExactlyOneIncidentOneAgentCallOneTicket() throws Exception {
        stubAgentServiceSuccess();
        String key = "concurrent-dup-" + System.nanoTime();
        String body = webhookJson(key, "payment-service 5xx spike, concurrent test");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<String>> task = () -> postWebhook(body);
            List<Future<ResponseEntity<String>>> futures = pool.invokeAll(List.of(task, task));

            List<String> statuses = futures.stream().map(f -> {
                try {
                    JsonNode json = objectMapper.readTree(f.get().getBody());
                    return json.get("status").asText();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            // Exactly one of the two concurrent requests must have won the
            // Redis reservation; the other must see it as a duplicate.
            assertThat(statuses).containsExactlyInAnyOrder("processing", "duplicate");
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        AGENT_SERVICE.verify(1, postRequestedFor(urlEqualTo("/triage")));
    }

    @Test
    void differentDedupKeys_produceIndependentIncidents() throws Exception {
        stubAgentServiceSuccess();
        String keyA = "distinct-a-" + System.nanoTime();
        String keyB = "distinct-b-" + System.nanoTime();

        ResponseEntity<String> a = postWebhook(webhookJson(keyA, "payment-service 5xx spike A"));
        ResponseEntity<String> b = postWebhook(webhookJson(keyB, "payment-service 5xx spike B"));

        String incidentA = objectMapper.readTree(a.getBody()).get("incident_id").asText();
        String incidentB = objectMapper.readTree(b.getBody()).get("incident_id").asText();
        assertThat(incidentA).isNotEqualTo(incidentB);
        AGENT_SERVICE.verify(2, postRequestedFor(urlEqualTo("/triage")));
    }
}
