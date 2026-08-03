package com.incidentintel.webhook;

import com.incidentintel.agent.AgentServiceClient;
import com.incidentintel.agent.AgentTriageRequest;
import com.incidentintel.agent.AgentTriageResponse;
import com.incidentintel.common.IncidentStatus;
import com.incidentintel.idempotency.IdempotencyKeyGenerator;
import com.incidentintel.idempotency.RedisIdempotencyService;
import com.incidentintel.incident.Incident;
import com.incidentintel.incident.IncidentRepository;
import com.incidentintel.ticket.Ticket;
import com.incidentintel.ticket.TicketRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Note: this class is deliberately NOT wrapped in a single @Transactional
 * method. Each Spring Data repository call (save/findById) already runs in
 * its own short transaction, and the blocking call to the agent service
 * (up to ~30s) must not be made while holding a database transaction open.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final IncidentRepository incidentRepository;
    private final TicketRepository ticketRepository;
    private final RedisIdempotencyService idempotencyService;
    private final AgentServiceClient agentServiceClient;
    private final MeterRegistry meterRegistry;

    public WebhookService(IncidentRepository incidentRepository, TicketRepository ticketRepository,
                           RedisIdempotencyService idempotencyService, AgentServiceClient agentServiceClient,
                           MeterRegistry meterRegistry) {
        this.incidentRepository = incidentRepository;
        this.ticketRepository = ticketRepository;
        this.idempotencyService = idempotencyService;
        this.agentServiceClient = agentServiceClient;
        this.meterRegistry = meterRegistry;
    }

    public WebhookResponse handle(PagerDutyWebhookRequest request) {
        PagerDutyWebhookRequest.Payload payload = request.payload();
        String idempotencyKey = IdempotencyKeyGenerator.generate(
                request.dedupKey(), request.routingKey(), payload.source(), payload.component(),
                payload.className(), payload.summary());

        UUID candidateId = UUID.randomUUID();
        boolean reserved = idempotencyService.tryReserve(idempotencyKey, candidateId);

        if (!reserved) {
            // Duplicate: return the existing incident/ticket without ever
            // calling the agent service (proving dedupe works without
            // burning an OpenAI call). Not part of triage_latency_seconds —
            // no triage happened.
            return handleDuplicate(idempotencyKey);
        }

        Incident incident = new Incident();
        incident.setId(candidateId);
        incident.setDedupKey(request.dedupKey());
        incident.setIdempotencyKey(idempotencyKey);
        incident.setRoutingKey(request.routingKey());
        incident.setSource(payload.source());
        incident.setSummary(payload.summary());
        incident.setSeverity(payload.severity());
        incident.setComponent(payload.component());
        incident.setGroupName(payload.group());
        incident.setClassName(payload.className());
        incident.setCustomDetails(payload.customDetails());
        incident.setStatus(IncidentStatus.RECEIVED);
        incidentRepository.save(incident);

        incident.setStatus(IncidentStatus.TRIAGING);
        incidentRepository.save(incident);

        Timer.Sample sample = Timer.start(meterRegistry);
        AgentTriageOutcome outcome = triageViaAgentService(incident, request);
        sample.stop(Timer.builder("triage_latency_seconds")
                .description("End-to-end latency of the synchronous webhook -> agent-service triage call")
                .tag("outcome", outcome.label())
                .publishPercentileHistogram()
                .register(meterRegistry));

        return new WebhookResponse("processing", incident.getId(), outcome.ticketId());
    }

    private record AgentTriageOutcome(String label, UUID ticketId) {
    }

    private AgentTriageOutcome triageViaAgentService(Incident incident, PagerDutyWebhookRequest request) {
        try {
            AgentTriageRequest agentRequest = new AgentTriageRequest(
                    incident.getId(), request.routingKey(), request.dedupKey(), request.payload());
            AgentTriageResponse agentResponse = agentServiceClient.triage(agentRequest);
            String label = agentResponse.decision() != null ? agentResponse.decision().toLowerCase() : "unknown";
            return new AgentTriageOutcome(label, agentResponse.ticketId());
        } catch (Exception e) {
            // The agent service's own error_handler node guarantees a FAILED
            // callback on any graph failure; this catch is a safety net for
            // failures in reaching the agent service at all (network error,
            // timeout) where no callback will ever arrive.
            log.error("agent-service call failed for incident {}", incident.getId(), e);
            incident.setStatus(IncidentStatus.FAILED);
            incidentRepository.save(incident);
            return new AgentTriageOutcome("failed", null);
        }
    }

    private WebhookResponse handleDuplicate(String idempotencyKey) {
        UUID existingId = idempotencyService.getExistingIncidentId(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency key reserved but missing its value in Redis: " + idempotencyKey));
        Incident existingIncident = incidentRepository.findById(existingId)
                .orElseThrow(() -> new IllegalStateException(
                        "Redis idempotency key points to a missing incident: " + existingId));
        UUID ticketId = ticketRepository.findByIncidentId(existingIncident.getId())
                .map(Ticket::getId)
                .orElse(null);
        return new WebhookResponse("duplicate", existingIncident.getId(), ticketId);
    }
}
