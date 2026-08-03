package com.incidentintel.webhook;

import java.util.UUID;

public record WebhookResponse(String status, UUID incidentId, UUID ticketId) {
}
