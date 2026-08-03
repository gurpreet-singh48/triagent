package com.incidentintel.internal;

import com.incidentintel.common.Decision;
import com.incidentintel.common.IncidentNotFoundException;
import com.incidentintel.common.IncidentStatus;
import com.incidentintel.common.TicketStatus;
import com.incidentintel.config.TriagentProperties;
import com.incidentintel.incident.Incident;
import com.incidentintel.incident.IncidentRepository;
import com.incidentintel.ticket.Ticket;
import com.incidentintel.ticket.TicketRepository;
import com.incidentintel.ticket.TicketRetrievedDoc;
import com.incidentintel.ticket.TicketRetrievedDocRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Persists the agent-service's triage result. This is the callback target
 * described in PLAN.md's cross-service design — in Phase 2 it is only
 * reachable via manual curl (the agent service doesn't exist yet); from
 * Phase 3 onward the webhook handler calls the agent service, which calls
 * back here automatically.
 */
@Service
public class TriageResultService {

    private final IncidentRepository incidentRepository;
    private final TicketRepository ticketRepository;
    private final TicketRetrievedDocRepository retrievedDocRepository;
    private final TriagentProperties properties;

    public TriageResultService(IncidentRepository incidentRepository, TicketRepository ticketRepository,
                                TicketRetrievedDocRepository retrievedDocRepository, TriagentProperties properties) {
        this.incidentRepository = incidentRepository;
        this.ticketRepository = ticketRepository;
        this.retrievedDocRepository = retrievedDocRepository;
        this.properties = properties;
    }

    @Transactional
    public TriageResultResponse record(TriageResultRequest request) {
        Incident incident = incidentRepository.findById(request.incidentId())
                .orElseThrow(() -> new IncidentNotFoundException(request.incidentId()));

        if (Decision.FAILED.name().equalsIgnoreCase(request.decision())) {
            incident.setStatus(IncidentStatus.FAILED);
            incidentRepository.save(incident);
            return new TriageResultResponse(null, IncidentStatus.FAILED.name());
        }

        double confidence = request.confidence() != null ? request.confidence() : 0.0;
        // Defense-in-depth: the backend recomputes the auto-vs-review split
        // itself rather than trusting the agent's decision label outright.
        TicketStatus ticketStatus = confidence >= properties.confidenceThreshold()
                ? TicketStatus.OPEN
                : TicketStatus.PENDING_REVIEW;

        Ticket ticket = new Ticket();
        ticket.setIncidentId(incident.getId());
        ticket.setStatus(ticketStatus);
        ticket.setPredictedTeam(request.predictedTeam());
        ticket.setPredictedCategory(request.predictedCategory());
        ticket.setPredictedSeverity(request.predictedSeverity());
        ticket.setConfidenceScore(request.confidence() != null ? BigDecimal.valueOf(request.confidence()) : null);
        ticket.setDecision(parseDecision(request.decision()));
        ticket.setRationale(request.rationale());
        ticket.setRedactedSummary(request.redactedSummary());
        ticketRepository.save(ticket);

        saveRetrievedDocs(ticket.getId(), request.retrievedDocs());

        incident.setStatus(IncidentStatus.TRIAGED);
        incidentRepository.save(incident);

        return new TriageResultResponse(ticket.getId(), ticketStatus.name());
    }

    private void saveRetrievedDocs(java.util.UUID ticketId, List<TriageResultRequest.RetrievedDocDto> docs) {
        if (docs == null) {
            return;
        }
        for (int i = 0; i < docs.size(); i++) {
            TriageResultRequest.RetrievedDocDto docDto = docs.get(i);
            TicketRetrievedDoc doc = new TicketRetrievedDoc();
            doc.setTicketId(ticketId);
            doc.setDocId(docDto.docId());
            doc.setDocTitle(docDto.title());
            doc.setDocSourceType(docDto.sourceType());
            doc.setScore(docDto.score() != null ? BigDecimal.valueOf(docDto.score()) : null);
            doc.setSnippet(docDto.snippet());
            doc.setRank(docDto.rank() != null ? docDto.rank() : i + 1);
            retrievedDocRepository.save(doc);
        }
    }

    private Decision parseDecision(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Decision.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
