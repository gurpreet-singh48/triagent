package com.incidentintel.ticket;

import com.incidentintel.common.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByIncidentId(UUID incidentId);

    Page<Ticket> findByPredictedTeamAndStatus(String predictedTeam, TicketStatus status, Pageable pageable);

    Page<Ticket> findByPredictedTeam(String predictedTeam, Pageable pageable);

    Page<Ticket> findByStatus(TicketStatus status, Pageable pageable);
}
