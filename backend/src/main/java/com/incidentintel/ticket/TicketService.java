package com.incidentintel.ticket;

import com.incidentintel.common.IllegalStateTransitionException;
import com.incidentintel.common.TicketNotFoundException;
import com.incidentintel.common.TicketStatus;
import com.incidentintel.incident.Incident;
import com.incidentintel.incident.IncidentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketRetrievedDocRepository retrievedDocRepository;
    private final IncidentRepository incidentRepository;

    public TicketService(TicketRepository ticketRepository, TicketRetrievedDocRepository retrievedDocRepository,
                          IncidentRepository incidentRepository) {
        this.ticketRepository = ticketRepository;
        this.retrievedDocRepository = retrievedDocRepository;
        this.incidentRepository = incidentRepository;
    }

    public Page<TicketResponse> list(String team, TicketStatus status, Pageable pageable) {
        Page<Ticket> page;
        if (team != null && status != null) {
            page = ticketRepository.findByPredictedTeamAndStatus(team, status, pageable);
        } else if (team != null) {
            page = ticketRepository.findByPredictedTeam(team, pageable);
        } else if (status != null) {
            page = ticketRepository.findByStatus(status, pageable);
        } else {
            page = ticketRepository.findAll(pageable);
        }
        return page.map(this::toResponse);
    }

    public TicketDetailResponse getDetail(UUID id) {
        Ticket ticket = ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
        Incident incident = incidentRepository.findById(ticket.getIncidentId())
                .orElseThrow(() -> new IllegalStateException("ticket references missing incident " + ticket.getIncidentId()));
        List<TicketRetrievedDoc> docs = retrievedDocRepository.findByTicketIdOrderByRankAsc(id);
        return toDetailResponse(ticket, incident, docs);
    }

    @Transactional
    public TicketDetailResponse approve(UUID id, String reviewedBy) {
        return reviewTransition(id, reviewedBy, TicketStatus.APPROVED);
    }

    @Transactional
    public TicketDetailResponse reject(UUID id, String reviewedBy) {
        return reviewTransition(id, reviewedBy, TicketStatus.REJECTED);
    }

    private TicketDetailResponse reviewTransition(UUID id, String reviewedBy, TicketStatus target) {
        Ticket ticket = ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
        if (ticket.getStatus() != TicketStatus.PENDING_REVIEW) {
            throw new IllegalStateTransitionException(
                    "ticket " + id + " is " + ticket.getStatus() + ", not PENDING_REVIEW — cannot transition to " + target);
        }
        ticket.setStatus(target);
        ticket.setReviewedBy(reviewedBy);
        ticket.setReviewedAt(OffsetDateTime.now());
        ticketRepository.save(ticket);
        return getDetail(id);
    }

    private TicketResponse toResponse(Ticket t) {
        return new TicketResponse(
                t.getId(), t.getIncidentId(),
                t.getStatus() != null ? t.getStatus().name() : null,
                t.getPredictedTeam(), t.getPredictedCategory(), t.getPredictedSeverity(),
                t.getConfidenceScore(),
                t.getDecision() != null ? t.getDecision().name() : null,
                t.getCreatedAt(), t.getUpdatedAt());
    }

    private TicketDetailResponse toDetailResponse(Ticket t, Incident incident, List<TicketRetrievedDoc> docs) {
        List<TicketDetailResponse.RetrievedDocResponse> docResponses = docs.stream()
                .map(d -> new TicketDetailResponse.RetrievedDocResponse(
                        d.getDocId(), d.getDocTitle(), d.getDocSourceType(), d.getScore(), d.getSnippet(), d.getRank()))
                .toList();

        TicketDetailResponse.IncidentSummary incidentSummary = new TicketDetailResponse.IncidentSummary(
                incident.getId(), incident.getSummary(), incident.getSeverity(), incident.getComponent(),
                incident.getSource(), incident.getReceivedAt());

        return new TicketDetailResponse(
                t.getId(),
                t.getStatus() != null ? t.getStatus().name() : null,
                t.getPredictedTeam(), t.getPredictedCategory(), t.getPredictedSeverity(),
                t.getConfidenceScore(),
                t.getDecision() != null ? t.getDecision().name() : null,
                t.getRationale(), t.getRedactedSummary(), t.getReviewedBy(), t.getReviewedAt(),
                t.getCreatedAt(), t.getUpdatedAt(),
                incidentSummary, docResponses);
    }
}
