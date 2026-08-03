package com.incidentintel.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record PagerDutyWebhookRequest(
        @NotBlank String routingKey,
        String eventAction,
        String dedupKey,
        @Valid @NotNull Payload payload,
        String client,
        String clientUrl
) {
    public record Payload(
            @NotBlank String summary,
            @NotBlank String source,
            String severity,
            String timestamp,
            String component,
            String group,
            @JsonProperty("class") String className,
            Map<String, Object> customDetails
    ) {
    }
}
