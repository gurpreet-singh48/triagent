CREATE TABLE incidents (
    id               UUID PRIMARY KEY,
    dedup_key        VARCHAR(255),
    idempotency_key  VARCHAR(255) NOT NULL,
    routing_key      VARCHAR(255),
    source           VARCHAR(255),
    summary          TEXT,
    severity         VARCHAR(32),
    component        VARCHAR(255),
    group_name       VARCHAR(255),
    class            VARCHAR(255),
    custom_details   JSONB,
    status           VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_incidents_idempotency_key ON incidents (idempotency_key);

CREATE TABLE tickets (
    id                 UUID PRIMARY KEY,
    incident_id        UUID NOT NULL REFERENCES incidents (id),
    status             VARCHAR(32) NOT NULL,
    predicted_team      VARCHAR(255),
    predicted_category  VARCHAR(255),
    predicted_severity  VARCHAR(32),
    confidence_score    NUMERIC(4,3),
    decision            VARCHAR(32),
    rationale           TEXT,
    redacted_summary    TEXT,
    reviewed_by         VARCHAR(255),
    reviewed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tickets_status ON tickets (status);
CREATE INDEX idx_tickets_predicted_team ON tickets (predicted_team);
CREATE INDEX idx_tickets_incident_id ON tickets (incident_id);

CREATE TABLE ticket_retrieved_docs (
    id                 UUID PRIMARY KEY,
    ticket_id          UUID NOT NULL REFERENCES tickets (id),
    doc_id             VARCHAR(255),
    doc_title          VARCHAR(500),
    doc_source_type    VARCHAR(32),
    score              NUMERIC(6,5),
    snippet            TEXT,
    rank               INT
);

CREATE INDEX idx_ticket_retrieved_docs_ticket_id ON ticket_retrieved_docs (ticket_id);
