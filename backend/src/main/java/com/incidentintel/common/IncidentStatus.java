package com.incidentintel.common;

public enum IncidentStatus {
    RECEIVED,
    TRIAGING,
    /** Agent-service call failed on a retryable error category; queued for
     * another attempt at {@code next_retry_at}. */
    RETRYING,
    TRIAGED,
    /** Terminal: either a non-retryable failure, or RETRYING exhausted its
     * attempt budget. */
    FAILED
}
