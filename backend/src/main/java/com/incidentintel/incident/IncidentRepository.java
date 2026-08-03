package com.incidentintel.incident;

import com.incidentintel.common.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    Optional<Incident> findByIdempotencyKey(String idempotencyKey);

    List<Incident> findByStatusAndNextRetryAtBefore(IncidentStatus status, OffsetDateTime cutoff);
}
