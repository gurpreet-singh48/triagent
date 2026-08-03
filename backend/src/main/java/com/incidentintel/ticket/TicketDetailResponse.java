package com.incidentintel.ticket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TicketDetailResponse(
        UUID id,
        String status,
        String predictedTeam,
        String predictedCategory,
        String predictedSeverity,
        BigDecimal confidenceScore,
        String decision,
        String rationale,
        String redactedSummary,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        IncidentSummary incident,
        List<RetrievedDocResponse> retrievedDocs
) {
    public record IncidentSummary(
            UUID id,
            String summary,
            String severity,
            String component,
            String source,
            OffsetDateTime receivedAt
    ) {
    }

    public record RetrievedDocResponse(
            String docId,
            String title,
            String sourceType,
            BigDecimal score,
            String snippet,
            Integer rank
    ) {
    }
}
