package com.incidentintel.common;

import java.util.UUID;

public class TicketNotFoundException extends RuntimeException {
    public TicketNotFoundException(UUID id) {
        super("ticket not found: " + id);
    }
}
