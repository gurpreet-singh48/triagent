package com.incidentintel.agent;

import com.incidentintel.webhook.PagerDutyWebhookRequest;

import java.util.UUID;

public record AgentTriageRequest(
        UUID incidentId,
        String routingKey,
        String dedupKey,
        PagerDutyWebhookRequest.Payload payload
) {
}
