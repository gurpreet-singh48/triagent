package com.incidentintel.common;

import java.util.UUID;

public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException(UUID id) {
        super("incident not found: " + id);
    }
}
