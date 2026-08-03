package com.incidentintel.webhook;

import com.incidentintel.common.IncidentStatus;
import com.incidentintel.incident.Incident;
import com.incidentintel.incident.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Polls for incidents in RETRYING whose next_retry_at has passed and
 * re-attempts triage for each. A database-backed dead-letter/retry queue —
 * no message broker needed at this system's volume; see WebhookService's
 * retry/backoff logic for the state machine this drives.
 */
@Component
public class IncidentRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(IncidentRetryScheduler.class);

    private final IncidentRepository incidentRepository;
    private final WebhookService webhookService;

    public IncidentRetryScheduler(IncidentRepository incidentRepository, WebhookService webhookService) {
        this.incidentRepository = incidentRepository;
        this.webhookService = webhookService;
    }

    @Scheduled(fixedDelayString = "${triagent.retry.poll-interval-ms:30000}")
    public void retryDueIncidents() {
        List<Incident> due = incidentRepository.findByStatusAndNextRetryAtBefore(
                IncidentStatus.RETRYING, OffsetDateTime.now());
        for (Incident incident : due) {
            log.info("retrying incident {} (attempt {})", incident.getId(), incident.getAttemptCount() + 1);
            try {
                webhookService.retry(incident);
            } catch (Exception e) {
                // retry() itself handles agent-service failures via the
                // same handleTriageFailure path; this only guards against
                // something unexpected so one bad incident can't stop the
                // rest of the batch from being processed.
                log.error("unexpected error retrying incident {}", incident.getId(), e);
            }
        }
    }
}
