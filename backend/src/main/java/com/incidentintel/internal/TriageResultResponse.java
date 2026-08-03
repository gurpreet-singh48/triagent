package com.incidentintel.internal;

import java.util.UUID;

public record TriageResultResponse(UUID ticketId, String status) {
}
