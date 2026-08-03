package com.incidentintel.webhook;

import com.incidentintel.agent.AgentServiceClient;
import com.incidentintel.agent.AgentTriageResponse;
import com.incidentintel.idempotency.RedisIdempotencyService;
import com.incidentintel.incident.Incident;
import com.incidentintel.incident.IncidentRepository;
import com.incidentintel.ticket.TicketRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebhookReservationCompensationTest {

    private PagerDutyWebhookRequest request(String dedupKey) {
        return new PagerDutyWebhookRequest(
                "R123", "trigger", dedupKey,
                new PagerDutyWebhookRequest.Payload(
                        "payment-service 5xx spike", "payment-service-prod-1", "critical", null,
                        "payment-service", "payments", "5xx-spike", Map.of("error_rate", 0.12)),
                "test", "http://localhost");
    }

    @Test
    void failedIncidentInsertReleasesReservationSoSecondRequestCanRetry() {
        IncidentRepository incidents = mock(IncidentRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        RedisIdempotencyService idempotency = mock(RedisIdempotencyService.class);
        AgentServiceClient agent = mock(AgentServiceClient.class);
        WebhookService service = new WebhookService(
                incidents, tickets, idempotency, agent, new SimpleMeterRegistry());

        when(idempotency.tryReserve(anyString(), any())).thenReturn(true, true);
        when(idempotency.release(anyString(), any())).thenReturn(true);
        when(incidents.save(any(Incident.class)))
                .thenThrow(new RuntimeException("simulated PostgreSQL insert failure"))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(new AgentTriageResponse(
                "triaged", null, "AUTO_TICKET", 0.95, "5xx-spike", "payment-service"))
                .when(agent).triage(any());

        PagerDutyWebhookRequest request = request("insert-failure-retry");

        assertThatThrownBy(() -> service.handle(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated PostgreSQL insert failure");
        verify(idempotency).release(anyString(), any());

        WebhookResponse retry = service.handle(request);
        assertThat(retry.status()).isEqualTo("processing");
        verify(agent).triage(any());
    }

    @Test
    void duplicateWaitsForWinningIncidentInsertInsteadOfReturningServerError() {
        IncidentRepository incidents = mock(IncidentRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        RedisIdempotencyService idempotency = mock(RedisIdempotencyService.class);
        AgentServiceClient agent = mock(AgentServiceClient.class);
        WebhookService service = new WebhookService(
                incidents, tickets, idempotency, agent, new SimpleMeterRegistry());

        UUID winningId = UUID.randomUUID();
        Incident winningIncident = new Incident();
        winningIncident.setId(winningId);
        when(idempotency.tryReserve(anyString(), any())).thenReturn(false);
        when(idempotency.getExistingIncidentId(anyString())).thenReturn(Optional.of(winningId));
        when(incidents.findById(winningId))
                .thenReturn(Optional.<Incident>empty(), Optional.empty(), Optional.of(winningIncident));
        when(tickets.findByIncidentId(winningId)).thenReturn(Optional.empty());

        WebhookResponse duplicate = service.handle(request("concurrent-duplicate"));

        assertThat(duplicate.status()).isEqualTo("duplicate");
        assertThat(duplicate.incidentId()).isEqualTo(winningId);
        assertThat(duplicate.ticketId()).isNull();
        verify(incidents, times(3)).findById(winningId);
        verifyNoInteractions(agent);
    }
}
