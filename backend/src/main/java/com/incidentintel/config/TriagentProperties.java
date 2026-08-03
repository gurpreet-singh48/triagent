package com.incidentintel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "triagent")
public record TriagentProperties(double confidenceThreshold, AgentService agentService, String internalServiceToken) {
    public record AgentService(String url, long connectTimeoutMs, long readTimeoutMs) {
    }
}
