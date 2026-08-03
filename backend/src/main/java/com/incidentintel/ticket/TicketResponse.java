package com.incidentintel.ticket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        UUID incidentId,
        String status,
        String predictedTeam,
        String predictedCategory,
        String predictedSeverity,
        BigDecimal confidenceScore,
        String decision,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
