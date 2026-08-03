package com.incidentintel.internal;

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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TriageResultService.record() is the callback the agent-service invokes
 * after triage. It must be idempotent: the agent-service may retry a
 * callback (e.g. it never received our response even though we processed
 * it), and that must not create a second ticket for the same incident.
 */
class TriageCallbackTest extends AbstractIntegrationTest {

    // Matches application.yml's `${INTERNAL_SERVICE_TOKEN:dev-local-internal-token}`
    // default, which applies here since AbstractIntegrationTest doesn't
    // override it.
    private static final String VALID_TOKEN = "dev-local-internal-token";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    private UUID createIncident() {
        Incident incident = new Incident();
        incident.setId(UUID.randomUUID());
        incident.setIdempotencyKey("callback-test-" + UUID.randomUUID());
        incident.setSource("payment-service-prod-1");
        incident.setSummary("payment-service 5xx spike");
        incident.setSeverity("critical");
        incident.setComponent("payment-service");
        incident.setStatus(IncidentStatus.TRIAGING);
        return incidentRepository.save(incident).getId();
    }

    private String callbackJson(UUID incidentId, String decision, double confidence) {
        return """
                {
                  "incident_id": "%s", "decision": "%s", "confidence": %s,
                  "predicted_team": "payment-service", "predicted_category": "5xx-spike",
                  "predicted_severity": "critical", "rationale": "elevated error rate matches known pattern",
                  "redacted_summary": "payment-service 5xx spike", "retrieved_docs": []
                }
                """.formatted(incidentId, decision, confidence);
    }

    private ResponseEntity<String> postCallback(String json) {
        return postCallback(json, VALID_TOKEN);
    }

    private ResponseEntity<String> postCallback(String json, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Internal-Service-Token", token);
        }
        return restTemplate.postForEntity("/api/internal/triage-results", new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void firstCallback_highConfidence_createsOpenAutoTicket() throws Exception {
        UUID incidentId = createIncident();
        ResponseEntity<String> response = postCallback(callbackJson(incidentId, "AUTO_TICKET", 0.95));
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.get("status").asText()).isEqualTo("OPEN");
        assertThat(ticketRepository.findByIncidentId(incidentId)).isPresent();
        assertThat(incidentRepository.findById(incidentId).orElseThrow().getStatus()).isEqualTo(IncidentStatus.TRIAGED);
    }

    @Test
    void firstCallback_lowConfidence_createsPendingReviewTicket() throws Exception {
        UUID incidentId = createIncident();
        ResponseEntity<String> response = postCallback(callbackJson(incidentId, "HUMAN_REVIEW", 0.55));
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.get("status").asText()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void failedDecision_marksIncidentFailedCreatesNoTicket() throws Exception {
        UUID incidentId = createIncident();
        String json = """
                {"incident_id": "%s", "decision": "FAILED"}
                """.formatted(incidentId);
        ResponseEntity<String> response = postCallback(json);
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.get("status").asText()).isEqualTo("FAILED");
        assertThat(body.get("ticket_id").isNull()).isTrue();
        assertThat(ticketRepository.findByIncidentId(incidentId)).isEmpty();
        assertThat(incidentRepository.findById(incidentId).orElseThrow().getStatus()).isEqualTo(IncidentStatus.FAILED);
    }

    @Test
    void callbackForUnknownIncidentId_returns404() {
        String json = callbackJson(UUID.randomUUID(), "AUTO_TICKET", 0.95);
        ResponseEntity<String> response = postCallback(json);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sequentialDuplicateCallback_returnsExistingTicketNoSecondRow() throws Exception {
        UUID incidentId = createIncident();
        ResponseEntity<String> first = postCallback(callbackJson(incidentId, "AUTO_TICKET", 0.95));
        JsonNode firstBody = objectMapper.readTree(first.getBody());
        String firstTicketId = firstBody.get("ticket_id").asText();

        // Retry with a DIFFERENT predicted team/confidence, simulating a
        // stale/duplicate agent-service retry — the original ticket must
        // win untouched, not be overwritten or duplicated.
        ResponseEntity<String> second = postCallback(callbackJson(incidentId, "HUMAN_REVIEW", 0.10));
        JsonNode secondBody = objectMapper.readTree(second.getBody());

        assertThat(secondBody.get("ticket_id").asText()).isEqualTo(firstTicketId);
        assertThat(secondBody.get("status").asText()).isEqualTo(firstBody.get("status").asText());
        assertThat(ticketRepository.findAll().stream().filter(t -> t.getIncidentId().equals(incidentId)).count())
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateCallbacks_raceOnUniqueConstraint_exactlyOneTicketRow() throws Exception {
        UUID incidentId = createIncident();
        String json = callbackJson(incidentId, "AUTO_TICKET", 0.95);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<String>> task = () -> postCallback(json);
            List<Future<ResponseEntity<String>>> futures = pool.invokeAll(List.of(task, task));

            List<String> ticketIds = futures.stream().map(f -> {
                try {
                    return objectMapper.readTree(f.get().getBody()).get("ticket_id").asText();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());

            // Both concurrent callbacks must agree on the SAME ticket id —
            // proving the loser of the DB race recovered by reading back
            // the winner's row instead of erroring or creating its own.
            assertThat(ticketIds).hasSize(2);
            assertThat(ticketIds.get(0)).isEqualTo(ticketIds.get(1));
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        long ticketCount = ticketRepository.findAll().stream().filter(t -> t.getIncidentId().equals(incidentId)).count();
        assertThat(ticketCount).isEqualTo(1);
    }

    @Test
    void callbackWithMissingToken_returns401AndCreatesNoTicket() {
        UUID incidentId = createIncident();
        ResponseEntity<String> response = postCallback(callbackJson(incidentId, "AUTO_TICKET", 0.95), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ticketRepository.findByIncidentId(incidentId)).isEmpty();
    }

    @Test
    void callbackWithWrongToken_returns401AndCreatesNoTicket() {
        UUID incidentId = createIncident();
        ResponseEntity<String> response = postCallback(callbackJson(incidentId, "AUTO_TICKET", 0.95), "not-the-right-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ticketRepository.findByIncidentId(incidentId)).isEmpty();
    }
}
