package com.incidentintel.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentintel.common.IncidentStatus;
import com.incidentintel.common.TicketStatus;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Human-in-the-loop review must only allow PENDING_REVIEW -> {APPROVED,
 * REJECTED}. Once reviewed, the decision is final — re-reviewing an
 * already-decided ticket (in either direction) must be rejected, not
 * silently allowed to flip the outcome.
 */
class TicketStateTransitionTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    private UUID createTicket(TicketStatus status) {
        Incident incident = new Incident();
        incident.setId(UUID.randomUUID());
        incident.setIdempotencyKey("ticket-state-test-" + UUID.randomUUID());
        incident.setSource("payment-service-prod-1");
        incident.setSummary("payment-service 5xx spike");
        incident.setSeverity("critical");
        incident.setStatus(IncidentStatus.TRIAGED);
        UUID incidentId = incidentRepository.save(incident).getId();

        Ticket ticket = new Ticket();
        ticket.setIncidentId(incidentId);
        ticket.setStatus(status);
        ticket.setPredictedTeam("payment-service");
        ticket.setPredictedCategory("5xx-spike");
        ticket.setPredictedSeverity("critical");
        return ticketRepository.save(ticket).getId();
    }

    private ResponseEntity<String> review(UUID ticketId, String action, String reviewedBy) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"reviewed_by": "%s"}
                """.formatted(reviewedBy);
        return restTemplate.postForEntity(
                "/api/tickets/" + ticketId + "/" + action, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void pendingReview_approve_succeeds() throws Exception {
        UUID ticketId = createTicket(TicketStatus.PENDING_REVIEW);
        ResponseEntity<String> response = review(ticketId, "approve", "alice");
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body.get("status").asText()).isEqualTo("APPROVED");
        assertThat(body.get("reviewed_by").asText()).isEqualTo("alice");
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getStatus()).isEqualTo(TicketStatus.APPROVED);
    }

    @Test
    void pendingReview_reject_succeeds() throws Exception {
        UUID ticketId = createTicket(TicketStatus.PENDING_REVIEW);
        ResponseEntity<String> response = review(ticketId, "reject", "bob");
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.get("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void approved_rejectAgain_returnsConflict() {
        UUID ticketId = createTicket(TicketStatus.APPROVED);
        ResponseEntity<String> response = review(ticketId, "reject", "carol");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getStatus()).isEqualTo(TicketStatus.APPROVED);
    }

    @Test
    void rejected_approveAgain_returnsConflict() {
        UUID ticketId = createTicket(TicketStatus.REJECTED);
        ResponseEntity<String> response = review(ticketId, "approve", "dave");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getStatus()).isEqualTo(TicketStatus.REJECTED);
    }

    @Test
    void openTicket_approve_returnsConflict_notPendingReview() {
        UUID ticketId = createTicket(TicketStatus.OPEN);
        ResponseEntity<String> response = review(ticketId, "approve", "erin");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reviewUnknownTicket_returns404() {
        ResponseEntity<String> response = review(UUID.randomUUID(), "approve", "frank");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
