package com.incidentintel.internal;

import java.util.List;
import java.util.UUID;

public record TriageResultRequest(
        UUID incidentId,
        String decision,
        Double confidence,
        String predictedTeam,
        String predictedCategory,
        String predictedSeverity,
        String rationale,
        String redactedSummary,
        List<RetrievedDocDto> retrievedDocs
) {
    public record RetrievedDocDto(
            String docId,
            String title,
            String sourceType,
            Double score,
            String snippet,
            Integer rank
    ) {
    }
}
