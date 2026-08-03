package com.incidentintel.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentintel.common.IncidentStatus;
import com.incidentintel.incident.Incident;
import com.incidentintel.incident.IncidentRepository;
import com.incidentintel.support.AbstractIntegrationTest;
import com.incidentintel.ticket.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class WebhookRetryTest extends AbstractIntegrationTest {

    // Matches application.yml's `${INTERNAL_SERVICE_TOKEN:dev-local-internal-token}`
    // default, which applies here since AbstractIntegrationTest doesn't override it.
    private static final String VALID_TOKEN = "dev-local-internal-token";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private IncidentRetryScheduler incidentRetryScheduler;

    private String webhookJson(String dedupKey) {
        return """
                {
                  "routing_key": "R123", "event_action": "trigger", "dedup_key": "%s",
                  "payload": {
                    "summary": "payment-service 5xx spike", "source": "payment-service-prod-1",
                    "severity": "critical", "timestamp": "2026-01-01T00:00:00Z",
                    "component": "payment-service", "group": "payments", "class": "5xx-spike",
                    "custom_details": {"error_rate": "0.12"}
                  },
                  "client": "test", "client_url": "http://localhost"
                }
                """.formatted(dedupKey);
    }

    private ResponseEntity<String> postWebhook(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/webhooks/pagerduty", new HttpEntity<>(json, headers), String.class);
    }

    private void postTriageResultCallback(UUID incidentId) {
        String json = """
                {
                  "incident_id": "%s", "decision": "AUTO_TICKET", "confidence": 0.95,
                  "predicted_team": "payment-service", "predicted_category": "5xx-spike",
                  "predicted_severity": "critical", "rationale": "elevated error rate matches known pattern",
                  "redacted_summary": "payment-service 5xx spike", "retrieved_docs": []
                }
                """.formatted(incidentId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Service-Token", VALID_TOKEN);
        restTemplate.postForEntity("/api/internal/triage-results", new HttpEntity<>(json, headers), String.class);
    }

    private Incident awaitIncident(UUID id) {
        return await().atMost(Duration.ofSeconds(10))
                .until(() -> incidentRepository.findById(id).orElse(null), i -> i != null);
    }

    private Incident awaitIncidentStatus(UUID id, IncidentStatus status) {
        return await().atMost(Duration.ofSeconds(10))
                .until(() -> incidentRepository.findById(id).orElse(null), i -> i != null && i.getStatus() == status);
    }

    @Test
    void agentServiceTimeout_firstAttempt_entersRetryingWithBackoff() throws Exception {
        stubFor(post(urlEqualTo("/triage")).willReturn(aResponse()
                .withFixedDelay(5000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"triaged\"}")));
        String key = "retry-timeout-" + System.nanoTime();

        ResponseEntity<String> response = postWebhook(webhookJson(key));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = objectMapper.readTree(response.getBody());
        UUID incidentId = UUID.fromString(body.get("incident_id").asText());

        Incident incident = awaitIncidentStatus(incidentId, IncidentStatus.RETRYING);
        assertThat(incident.getAttemptCount()).isEqualTo(1);
        assertThat(incident.getErrorCategory()).isEqualTo("timeout_or_connection_error");
        assertThat(incident.getFailureStage()).isEqualTo("agent_call");
        assertThat(incident.getNextRetryAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void agentServiceHttp4xx_isNotRetryable_goesStraightToFailed() throws Exception {
        stubFor(post(urlEqualTo("/triage")).willReturn(aResponse().withStatus(400)));
        String key = "retry-4xx-" + System.nanoTime();

        ResponseEntity<String> response = postWebhook(webhookJson(key));
        JsonNode body = objectMapper.readTree(response.getBody());
        UUID incidentId = UUID.fromString(body.get("incident_id").asText());

        Incident incident = awaitIncident(incidentId);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.FAILED);
        assertThat(incident.getAttemptCount()).isEqualTo(1);
        assertThat(incident.getErrorCategory()).isEqualTo("http_4xx");
    }

    private UUID createRetryingIncident(int attemptCount) {
        Incident incident = new Incident();
        incident.setId(UUID.randomUUID());
        incident.setIdempotencyKey("scheduler-test-" + UUID.randomUUID());
        incident.setSummary("payment-service 5xx spike");
        incident.setSource("payment-service-prod-1");
        incident.setSeverity("critical");
        incident.setComponent("payment-service");
        incident.setGroupName("payments");
        incident.setClassName("5xx-spike");
        incident.setCustomDetails(Map.of("error_rate", "0.12"));
        incident.setStatus(IncidentStatus.RETRYING);
        incident.setAttemptCount(attemptCount);
        incident.setFailureStage("agent_call");
        incident.setErrorCategory("timeout_or_connection_error");
        incident.setNextRetryAt(OffsetDateTime.now().minusSeconds(5));
        return incidentRepository.save(incident).getId();
    }

    @Test
    void scheduler_picksUpDueIncident_andSucceeds() {
        UUID incidentId = createRetryingIncident(1);
        stubFor(post(urlEqualTo("/triage")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"accepted\"}")));

        incidentRetryScheduler.retryDueIncidents();
        postTriageResultCallback(incidentId);

        Incident incident = awaitIncidentStatus(incidentId, IncidentStatus.TRIAGED);
        assertThat(ticketRepository.findByIncidentId(incident.getId())).isPresent();
    }

    @Test
    void scheduler_exhaustsMaxAttempts_marksFailed() {
        // Already failed twice; this is the 3rd (final) attempt.
        UUID incidentId = createRetryingIncident(2);
        stubFor(post(urlEqualTo("/triage")).willReturn(aResponse().withStatus(503)));

        incidentRetryScheduler.retryDueIncidents();

        Incident incident = awaitIncidentStatus(incidentId, IncidentStatus.FAILED);
        assertThat(incident.getAttemptCount()).isEqualTo(3);
        assertThat(incident.getNextRetryAt()).isNull();
    }
}
