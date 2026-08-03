package com.incidentintel.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentintel.common.IncidentStatus;
import com.incidentintel.incident.Incident;
import com.incidentintel.incident.IncidentRepository;
import com.incidentintel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Webhook-side failure handling: the backend must never leave an incident
 * stuck in TRIAGING, and must never 500 on a malformed request.
 */
class WebhookFailureTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentRepository incidentRepository;

    private ResponseEntity<String> postWebhook(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/webhooks/pagerduty", new HttpEntity<>(json, headers), String.class);
    }

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

    private Incident awaitIncident(String incidentId) {
        UUID id = UUID.fromString(incidentId);
        return await().atMost(Duration.ofSeconds(10))
                .until(() -> incidentRepository.findById(id).orElse(null), i -> i != null);
    }

    @Test
    void invalidPayload_missingRequiredFields_returns400NotServerError() {
        String malformed = """
                {"routing_key": "R123", "event_action": "trigger", "dedup_key": "bad-payload-1"}
                """; // missing required `payload` entirely
        ResponseEntity<String> response = postWebhook(malformed);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void agentServiceReturns500_incidentMarkedFailedNoTicket() throws Exception {
        stubFor(post(urlEqualTo("/triage")).willReturn(aResponse().withStatus(500)));
        String key = "agent-5xx-" + System.nanoTime();

        ResponseEntity<String> response = postWebhook(webhookJson(key));
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("ticket_id").isNull()).isTrue();

        Incident incident = awaitIncident(body.get("incident_id").asText());
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.FAILED);
    }

    @Test
    void agentServiceUnreachable_incidentMarkedFailedNoException() throws Exception {
        // No stub registered + WireMock configured to actually refuse the
        // connection for this path: simulate by pointing at a closed port
        // via a fault instead, which is closer to "service down" than 404.
        stubFor(post(urlEqualTo("/triage")).willReturn(aResponse().withFault(
                com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        String key = "agent-unreachable-" + System.nanoTime();

        ResponseEntity<String> response = postWebhook(webhookJson(key));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("ticket_id").isNull()).isTrue();

        Incident incident = awaitIncident(body.get("incident_id").asText());
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.FAILED);
    }

    @Test
    void agentServiceTimesOut_incidentMarkedFailed() throws Exception {
        // Base class configures a 2s read timeout; delay the stub response
        // well past that so the RestClient call itself times out.
        stubFor(post(urlEqualTo("/triage")).willReturn(aResponse()
                .withFixedDelay(5000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"triaged\"}")));
        String key = "agent-timeout-" + System.nanoTime();

        ResponseEntity<String> response = postWebhook(webhookJson(key));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = objectMapper.readTree(response.getBody());

        Incident incident = awaitIncident(body.get("incident_id").asText());
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.FAILED);
    }
}
