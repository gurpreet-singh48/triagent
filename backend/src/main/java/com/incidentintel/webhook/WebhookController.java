package com.incidentintel.webhook;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/pagerduty")
    public ResponseEntity<WebhookResponse> receive(@Valid @RequestBody PagerDutyWebhookRequest request) {
        WebhookResponse response = webhookService.handle(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
