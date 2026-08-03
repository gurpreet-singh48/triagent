package com.incidentintel.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal")
public class InternalTriageResultController {

    private final TriageResultService triageResultService;

    public InternalTriageResultController(TriageResultService triageResultService) {
        this.triageResultService = triageResultService;
    }

    @PostMapping("/triage-results")
    public TriageResultResponse receive(@RequestBody TriageResultRequest request) {
        return triageResultService.record(request);
    }
}
