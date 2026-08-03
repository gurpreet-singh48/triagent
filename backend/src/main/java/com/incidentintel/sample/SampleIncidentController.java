package com.incidentintel.sample;

import com.incidentintel.webhook.WebhookResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/demo-only endpoint: wires the frontend's "Trigger Sample Incident"
 * button to the same shared synthetic-generator templates the eval harness
 * uses, without needing PagerDuty or the Python CLI.
 */
@RestController
@RequestMapping("/api/dev")
public class SampleIncidentController {

    private final SampleIncidentService sampleIncidentService;

    public SampleIncidentController(SampleIncidentService sampleIncidentService) {
        this.sampleIncidentService = sampleIncidentService;
    }

    @PostMapping("/sample-incidents")
    public WebhookResponse trigger() {
        return sampleIncidentService.trigger();
    }
}
