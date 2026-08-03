package com.incidentintel.internal;

import com.incidentintel.common.Decision;
import com.incidentintel.common.IncidentNotFoundException;
import com.incidentintel.common.IncidentStatus;
import com.incidentintel.incident.Incident;
import com.incidentintel.incident.IncidentRepository;
import com.incidentintel.ticket.Ticket;
import com.incidentintel.ticket.TicketRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Persists the agent-service's triage result. This is the callback target
 * described in PLAN.md's cross-service design.
 *
 * Deliberately NOT itself {@code @Transactional}: the actual ticket INSERT
 * (which can lose a race on the {@code uk_ticket_incident} unique
 * constraint against a concurrent duplicate callback) lives in {@link
 * TicketCreationTransaction}'s own transaction. If that method's
 * transaction were the same one this method ran in, a constraint violation
 * would leave the whole transaction/connection in Postgres's "current
 * transaction is aborted" state, and the recovery lookup below would fail
 * too instead of cleanly reading back the winner's ticket.
 */
@Service
public class TriageResultService {

    private final IncidentRepository incidentRepository;
    private final TicketRepository ticketRepository;
    private final TicketCreationTransaction ticketCreationTransaction;

    public TriageResultService(IncidentRepository incidentRepository, TicketRepository ticketRepository,
                                TicketCreationTransaction ticketCreationTransaction) {
        this.incidentRepository = incidentRepository;
        this.ticketRepository = ticketRepository;
        this.ticketCreationTransaction = ticketCreationTransaction;
    }

    public TriageResultResponse record(TriageResultRequest request) {
        Incident incident = incidentRepository.findById(request.incidentId())
                .orElseThrow(() -> new IncidentNotFoundException(request.incidentId()));

        // Idempotency: the agent-service may retry a callback (network blip
        // after the first callback actually succeeded, at-least-once retry
        // logic, etc). A second callback for the same incident must return
        // the ticket already created, not create a second one.
        var existingTicket = ticketRepository.findByIncidentId(incident.getId());
        if (existingTicket.isPresent()) {
            Ticket ticket = existingTicket.get();
            return new TriageResultResponse(ticket.getId(), ticket.getStatus().name());
        }

        if (Decision.FAILED.name().equalsIgnoreCase(request.decision())) {
            ticketCreationTransaction.markIncidentFailed(incident.getId());
            return new TriageResultResponse(null, IncidentStatus.FAILED.name());
        }

        try {
            Ticket ticket = ticketCreationTransaction.createTicket(incident.getId(), request);
            return new TriageResultResponse(ticket.getId(), ticket.getStatus().name());
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent duplicate callback for the
            // same incident_id — the unique constraint (uk_ticket_incident)
            // caught what the findByIncidentId check above didn't. The
            // other request's ticket is authoritative; return it. This read
            // runs in a fresh transaction (this method isn't itself
            // @Transactional), not the poisoned one that just failed.
            return ticketRepository.findByIncidentId(incident.getId())
                    .map(t -> new TriageResultResponse(t.getId(), t.getStatus().name()))
                    .orElseThrow(() -> e);
        }
    }
}
