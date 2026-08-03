package com.incidentintel.sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentintel.webhook.PagerDutyWebhookRequest;
import com.incidentintel.webhook.WebhookResponse;
import com.incidentintel.webhook.WebhookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dev/demo-only: triggers a synthetic incident from the same shared JSON
 * templates synthetic-generator/generate.py uses (see PLAN.md), wiring the
 * frontend's "Trigger Sample Incident" button without needing the Python
 * CLI or a real PagerDuty account. Reuses WebhookService.handle() directly
 * so idempotency and the agent-service call path aren't duplicated here.
 */
@Service
public class SampleIncidentService {

    private final WebhookService webhookService;
    private final List<SyntheticTemplate> templates;

    public SampleIncidentService(WebhookService webhookService, ObjectMapper objectMapper,
                                  @Value("${triagent.synthetic-templates-dir:synthetic-generator/templates}") String templatesDir) {
        this.webhookService = webhookService;
        this.templates = loadTemplates(objectMapper, templatesDir);
    }

    private List<SyntheticTemplate> loadTemplates(ObjectMapper objectMapper, String dir) {
        File[] files = new File(dir).listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            throw new IllegalStateException("no synthetic templates found under " + dir);
        }
        List<SyntheticTemplate> result = new ArrayList<>();
        for (File file : files) {
            try {
                result.add(objectMapper.readValue(file, SyntheticTemplate.class));
            } catch (IOException e) {
                throw new IllegalStateException("failed to parse synthetic template " + file, e);
            }
        }
        result.sort(Comparator.comparing(SyntheticTemplate::category));
        return result;
    }

    public WebhookResponse trigger() {
        SyntheticTemplate template = templates.get(ThreadLocalRandom.current().nextInt(templates.size()));

        Map<String, Object> fieldValues = new LinkedHashMap<>();
        for (SyntheticTemplate.TemplateField field : template.fields()) {
            fieldValues.put(field.name(), generateValue(field));
        }

        String summary = template.summaryTemplate();
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            summary = summary.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }

        int instance = ThreadLocalRandom.current().nextInt(1, 10);
        String dedupKey = "synthetic-" + UUID.randomUUID();

        PagerDutyWebhookRequest.Payload payload = new PagerDutyWebhookRequest.Payload(
                summary,
                template.sourcePrefix() + "-" + instance,
                template.expectedSeverity(),
                OffsetDateTime.now().toString(),
                template.component(),
                template.group(),
                template.category(),
                fieldValues
        );

        PagerDutyWebhookRequest request = new PagerDutyWebhookRequest(
                "Rsynthetic", "trigger", dedupKey, payload,
                "Synthetic Incident Generator (UI)", "http://localhost:5173"
        );

        return webhookService.handle(request);
    }

    private Object generateValue(SyntheticTemplate.TemplateField field) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if ("int".equals(field.type())) {
            return random.nextInt((int) field.min(), (int) field.max() + 1);
        }
        double raw = field.min() + random.nextDouble() * (field.max() - field.min());
        int decimals = field.decimals() != null ? field.decimals() : 2;
        double scale = Math.pow(10, decimals);
        return Math.round(raw * scale) / scale;
    }
}
