package com.incidentintel.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketRetrievedDocRepository extends JpaRepository<TicketRetrievedDoc, UUID> {
    List<TicketRetrievedDoc> findByTicketIdOrderByRankAsc(UUID ticketId);
}
