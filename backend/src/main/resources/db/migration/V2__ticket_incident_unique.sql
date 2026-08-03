-- Enforces at the DB level what TriageResultService already enforces in code:
-- an incident can have at most one ticket. Without this, a retried
-- agent-service callback (network blip after the first callback succeeded,
-- agent-service retry logic, etc.) could race past the application-level
-- findByIncidentId check and insert a second ticket for the same incident.
ALTER TABLE tickets ADD CONSTRAINT uk_ticket_incident UNIQUE (incident_id);
