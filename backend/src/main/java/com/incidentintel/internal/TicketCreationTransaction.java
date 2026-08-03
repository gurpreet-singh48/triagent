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
import java.util.UUID;

/**
 * The transactional boundary around actually writing a ticket. Split out
 * from {@link TriageResultService} so that a unique-constraint violation
 * here rolls back only this transaction — see that class's Javadoc for why
 * that separation matters for the duplicate-callback recovery path.
 */
@Service
class TicketCreationTransaction {

    private final IncidentRepository incidentRepository;
    private final TicketRepository ticketRepository;
    private final TicketRetrievedDocRepository retrievedDocRepository;
    private final TriagentProperties properties;

    TicketCreationTransaction(IncidentRepository incidentRepository, TicketRepository ticketRepository,
                               TicketRetrievedDocRepository retrievedDocRepository, TriagentProperties properties) {
        this.incidentRepository = incidentRepository;
        this.ticketRepository = ticketRepository;
        this.retrievedDocRepository = retrievedDocRepository;
        this.properties = properties;
    }

    @Transactional
    public void markIncidentFailed(UUID incidentId) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
        incident.setStatus(IncidentStatus.FAILED);
        incidentRepository.save(incident);
    }

    @Transactional
    public Ticket createTicket(UUID incidentId, TriageResultRequest request) {
        double confidence = request.confidence() != null ? request.confidence() : 0.0;
        // Defense-in-depth: the backend recomputes the auto-vs-review split
        // itself rather than trusting the agent's decision label outright.
        TicketStatus ticketStatus = confidence >= properties.confidenceThreshold()
                ? TicketStatus.OPEN
                : TicketStatus.PENDING_REVIEW;

        Ticket ticket = new Ticket();
        ticket.setIncidentId(incidentId);
        ticket.setStatus(ticketStatus);
        ticket.setPredictedTeam(request.predictedTeam());
        ticket.setPredictedCategory(request.predictedCategory());
        ticket.setPredictedSeverity(request.predictedSeverity());
        ticket.setConfidenceScore(request.confidence() != null ? BigDecimal.valueOf(request.confidence()) : null);
        ticket.setDecision(parseDecision(request.decision()));
        ticket.setRationale(request.rationale());
        ticket.setRedactedSummary(request.redactedSummary());

        // saveAndFlush (not save): forces the unique-constraint check to
        // happen now, inside this transaction, rather than being deferred
        // to commit — the caller needs the exception to surface from this
        // call so it knows to fall back to reading the winner's row.
        ticketRepository.saveAndFlush(ticket);

        saveRetrievedDocs(ticket.getId(), request.retrievedDocs());

        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
        incident.setStatus(IncidentStatus.TRIAGED);
        incidentRepository.save(incident);

        return ticket;
    }

    private void saveRetrievedDocs(UUID ticketId, List<TriageResultRequest.RetrievedDocDto> docs) {
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
