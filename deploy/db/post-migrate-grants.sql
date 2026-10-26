-- Run after every Flyway migration (as cas_owner) to keep append-only tables append-only for cas_app.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events, security_events FROM cas_app;
REVOKE UPDATE, DELETE, TRUNCATE ON flyway_schema_history FROM cas_app;
GRANT SELECT, INSERT ON audit_events, security_events TO cas_app;
