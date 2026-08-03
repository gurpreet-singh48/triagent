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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Note: this class is deliberately NOT wrapped in a single @Transactional
 * method. Each Spring Data repository call (save/findById) already runs in
 * its own short transaction, and the blocking call to the agent service
 * (up to ~30s) must not be made while holding a database transaction open.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** RECEIVED -> TRIAGING -> RETRYING -> {TRIAGED, FAILED}. A database-
     * backed dead-letter/retry queue: IncidentRetryScheduler polls for
     * RETRYING rows whose next_retry_at has passed and re-attempts them —
     * no message broker needed at this system's volume. */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

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
        try {
            incidentRepository.save(incident);
        } catch (RuntimeException e) {
            boolean released = idempotencyService.release(idempotencyKey, candidateId);
            log.warn("incident insert failed after reserving idempotency key {}; reservation released={}",
                    idempotencyKey, released, e);
            throw e;
        }

        incident.setStatus(IncidentStatus.TRIAGING);
        incidentRepository.save(incident);

        AgentTriageOutcome outcome = attemptTriage(incident, buildAgentRequest(incident, request));
        return new WebhookResponse("processing", incident.getId(), outcome.ticketId());
    }

    /**
     * Re-attempts triage for an incident already in RETRYING with
     * next_retry_at due — called by IncidentRetryScheduler. Rebuilds the
     * agent-service request from the incident's own persisted fields since
     * the original webhook request object is long gone.
     */
    public void retry(Incident incident) {
        incident.setStatus(IncidentStatus.TRIAGING);
        incidentRepository.save(incident);
        attemptTriage(incident, buildAgentRequest(incident, null));
    }

    private AgentTriageRequest buildAgentRequest(Incident incident, PagerDutyWebhookRequest originalRequest) {
        if (originalRequest != null) {
            return new AgentTriageRequest(
                    incident.getId(), originalRequest.routingKey(), originalRequest.dedupKey(), originalRequest.payload());
        }
        PagerDutyWebhookRequest.Payload payload = new PagerDutyWebhookRequest.Payload(
                incident.getSummary(), incident.getSource(), incident.getSeverity(),
                incident.getReceivedAt() != null ? incident.getReceivedAt().toString() : null,
                incident.getComponent(), incident.getGroupName(), incident.getClassName(), incident.getCustomDetails());
        return new AgentTriageRequest(incident.getId(), incident.getRoutingKey(), incident.getDedupKey(), payload);
    }

    private record AgentTriageOutcome(String label, UUID ticketId) {
    }

    private AgentTriageOutcome attemptTriage(Incident incident, AgentTriageRequest agentRequest) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AgentTriageResponse agentResponse = agentServiceClient.triage(agentRequest);
            String label = agentResponse.decision() != null ? agentResponse.decision().toLowerCase() : "unknown";
            sample.stop(triageLatencyTimer(label));
            return new AgentTriageOutcome(label, agentResponse.ticketId());
        } catch (Exception e) {
            // The agent service's own error_handler node guarantees a FAILED
            // callback on any graph failure; this catch is a safety net for
            // failures in reaching the agent service at all (network error,
            // timeout, 5xx) where no callback will ever arrive.
            AgentTriageOutcome outcome = handleTriageFailure(incident, e);
            sample.stop(triageLatencyTimer(outcome.label()));
            return outcome;
        }
    }

    private Timer triageLatencyTimer(String outcomeLabel) {
        return Timer.builder("triage_latency_seconds")
                .description("End-to-end latency of the synchronous webhook -> agent-service triage call")
                .tag("outcome", outcomeLabel)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    private AgentTriageOutcome handleTriageFailure(Incident incident, Exception e) {
        String category = categorize(e);
        int attempts = incident.getAttemptCount() + 1;
        log.error("agent-service call failed for incident {} (attempt {}, category {})",
                incident.getId(), attempts, category, e);

        incident.setAttemptCount(attempts);
        incident.setFailureStage("agent_call");
        incident.setErrorCategory(category);
        incident.setLastError(truncate(String.valueOf(e), 2000));

        boolean retryable = category.equals("timeout_or_connection_error") || category.equals("http_5xx");
        if (retryable && attempts < MAX_ATTEMPTS) {
            incident.setStatus(IncidentStatus.RETRYING);
            incident.setNextRetryAt(OffsetDateTime.now().plus(backoff(attempts)));
            incidentRepository.save(incident);
            return new AgentTriageOutcome("retrying", null);
        }

        incident.setStatus(IncidentStatus.FAILED);
        incident.setNextRetryAt(null);
        incidentRepository.save(incident);
        return new AgentTriageOutcome("failed", null);
    }

    private String categorize(Exception e) {
        if (e instanceof ResourceAccessException) {
            return "timeout_or_connection_error";
        }
        if (e instanceof HttpServerErrorException) {
            return "http_5xx";
        }
        if (e instanceof HttpClientErrorException) {
            return "http_4xx";
        }
        return "unknown";
    }

    /** Exponential backoff with jitter, capped at MAX_BACKOFF: attempt 1 ->
     * ~30-40s, attempt 2 -> ~60-70s, attempt 3 -> ~120-130s, ... */
    private Duration backoff(int attempt) {
        long baseSeconds = BASE_BACKOFF.toSeconds() * (1L << (attempt - 1));
        long cappedSeconds = Math.min(baseSeconds, MAX_BACKOFF.toSeconds());
        long jitterSeconds = ThreadLocalRandom.current().nextLong(0, 10);
        return Duration.ofSeconds(cappedSeconds + jitterSeconds);
    }

    private String truncate(String s, int maxLength) {
        return s.length() <= maxLength ? s : s.substring(0, maxLength);
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
