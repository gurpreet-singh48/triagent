package com.incidentintel.sample;

import java.util.List;

public record SyntheticTemplate(
        String category,
        String expectedTeam,
        String expectedSeverity,
        String component,
        String group,
        String sourcePrefix,
        String summaryTemplate,
        List<TemplateField> fields
) {
    public record TemplateField(String name, String type, double min, double max, Integer decimals) {
    }
}
