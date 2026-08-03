package com.incidentintel.common;

public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
