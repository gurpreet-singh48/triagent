package com.incidentintel.internal;

import com.incidentintel.common.UnauthorizedException;
import com.incidentintel.config.TriagentProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /api/internal/* is meant to be reachable only by the agent-service, never
 * by an external caller. Within this single-network docker-compose setup
 * there's no network boundary enforcing that, so every request here is
 * checked against a shared secret instead — anyone who can reach this port
 * can otherwise call it directly and fabricate a ticket for any incident.
 *
 * This is a portfolio-appropriate control, not a production one: a shared
 * static token has no rotation, no per-caller identity, and leaks just as
 * easily as any other bearer credential. A production deployment would use
 * service identity (mTLS, SPIFFE/SPIRE), signed requests, or short-lived
 * credentials (e.g. a scoped JWT minted per-request) instead of a single
 * long-lived shared string.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalTriageResultController {

    private static final String TOKEN_HEADER = "X-Internal-Service-Token";

    private final TriageResultService triageResultService;
    private final TriagentProperties properties;

    public InternalTriageResultController(TriageResultService triageResultService, TriagentProperties properties) {
        this.triageResultService = triageResultService;
        this.properties = properties;
    }

    @PostMapping("/triage-results")
    public TriageResultResponse receive(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody TriageResultRequest request) {
        if (token == null || !constantTimeEquals(token, properties.internalServiceToken())) {
            throw new UnauthorizedException("missing or invalid " + TOKEN_HEADER);
        }
        return triageResultService.record(request);
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
