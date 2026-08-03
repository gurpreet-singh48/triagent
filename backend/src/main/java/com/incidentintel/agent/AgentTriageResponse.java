package com.incidentintel.agent;

import java.util.UUID;

public record AgentTriageResponse(
        String status,
        UUID ticketId,
        String decision,
        Double confidence,
        String category,
        String predictedTeam
) {
}
