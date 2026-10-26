-- Separation of database privileges (run once per environment as a superuser / DBA).
-- cas_owner: owns the schema and runs Flyway migrations (CAS_FLYWAY_USER).
-- cas_app:   runtime role used by the application (CAS_DB_USER); cannot alter schema and can only
--            INSERT/SELECT the audit tables, so the append-only property does not rely on triggers alone.
-- Replace the passwords via your secrets manager; never commit real values.

CREATE ROLE cas_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE cas_app LOGIN PASSWORD :'app_password';
CREATE DATABASE cas OWNER cas_owner;
\connect cas
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO cas_app;

-- Default privileges for tables Flyway creates as cas_owner.
ALTER DEFAULT PRIVILEGES FOR ROLE cas_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cas_app;
ALTER DEFAULT PRIVILEGES FOR ROLE cas_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO cas_app;
ALTER DEFAULT PRIVILEGES FOR ROLE cas_owner IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO cas_app;
