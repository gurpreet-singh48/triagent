package com.incidentintel.agent;

import com.incidentintel.config.TriagentProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AgentServiceClient {

    private final RestClient restClient;

    public AgentServiceClient(TriagentProperties properties, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(properties.agentService().url()).build();
    }

    public AgentTriageResponse triage(AgentTriageRequest request) {
        return restClient.post()
                .uri("/triage")
                .body(request)
                .retrieve()
                .body(AgentTriageResponse.class);
    }
}
