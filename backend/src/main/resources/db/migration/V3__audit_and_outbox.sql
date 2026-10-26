-- Append-only, hash-chained audit and security event stores, plus the notification outbox.
-- See docs/adr/ADR-003-audit-integrity.md. Tamper-evident, not tamper-proof: a database
-- superuser can still disable triggers, which is why runtime grants (deploy/db/roles.sql)
-- and sealed WORM exports are part of the control.

CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID         NOT NULL UNIQUE,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    actor_user_id   UUID,
    actor_username  VARCHAR(64),
    action          VARCHAR(80)  NOT NULL,
    resource_type   VARCHAR(80)  NOT NULL,
    resource_id     VARCHAR(80),
    outcome         VARCHAR(20)  NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    correlation_id  VARCHAR(64),
    client_ip       VARCHAR(45),
    details         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    prev_hash       CHAR(64)     NOT NULL,
    row_hash        CHAR(64)     NOT NULL
);
CREATE INDEX ix_audit_events_occurred ON audit_events (occurred_at);
CREATE INDEX ix_audit_events_actor ON audit_events (actor_user_id, occurred_at);
CREATE INDEX ix_audit_events_resource ON audit_events (resource_type, resource_id);
CREATE INDEX ix_audit_events_action ON audit_events (action);

CREATE TABLE security_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID         NOT NULL UNIQUE,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    actor_user_id   UUID,
    actor_username  VARCHAR(64),
    action          VARCHAR(80)  NOT NULL,
    resource_type   VARCHAR(80)  NOT NULL,
    resource_id     VARCHAR(80),
    outcome         VARCHAR(20)  NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    correlation_id  VARCHAR(64),
    client_ip       VARCHAR(45),
    details         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    severity        VARCHAR(10)  NOT NULL CHECK (severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH')),
    prev_hash       CHAR(64)     NOT NULL,
    row_hash        CHAR(64)     NOT NULL
);
CREATE INDEX ix_security_events_occurred ON security_events (occurred_at);
CREATE INDEX ix_security_events_actor ON security_events (actor_user_id, occurred_at);
CREATE INDEX ix_security_events_action ON security_events (action, occurred_at);

-- Canonical row representation shared by the insert trigger and the verification query.
CREATE FUNCTION cas_event_hash(
    p_prev_hash TEXT, p_event_id UUID, p_occurred_at TIMESTAMPTZ, p_actor_user_id UUID,
    p_actor_username TEXT, p_action TEXT, p_resource_type TEXT, p_resource_id TEXT,
    p_outcome TEXT, p_correlation_id TEXT, p_client_ip TEXT, p_details JSONB, p_severity TEXT)
RETURNS CHAR(64)
LANGUAGE sql IMMUTABLE AS $$
    SELECT encode(sha256(convert_to(
        p_prev_hash || '|' || p_event_id::text || '|' ||
        to_char(p_occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|' ||
        coalesce(p_actor_user_id::text, '') || '|' || coalesce(p_actor_username, '') || '|' ||
        p_action || '|' || p_resource_type || '|' || coalesce(p_resource_id, '') || '|' ||
        p_outcome || '|' || coalesce(p_correlation_id, '') || '|' || coalesce(p_client_ip, '') || '|' ||
        p_details::text || '|' || coalesce(p_severity, ''),
        'UTF8')), 'hex')
$$;

CREATE FUNCTION cas_event_chain() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_prev TEXT;
    v_severity TEXT;
BEGIN
    -- Serialise inserts per table so the chain is strictly linear. The lock is held until the
    -- inserting transaction ends; the id is (re)assigned under the lock so id order equals
    -- chain order. Requires READ COMMITTED so the latest committed row is visible here.
    PERFORM pg_advisory_xact_lock(hashtext('cas_chain_' || TG_TABLE_NAME));
    NEW.id := nextval(pg_get_serial_sequence(TG_TABLE_NAME, 'id'));
    EXECUTE format('SELECT row_hash FROM %I ORDER BY id DESC LIMIT 1', TG_TABLE_NAME) INTO v_prev;
    v_prev := coalesce(v_prev, repeat('0', 64));
    IF TG_TABLE_NAME = 'security_events' THEN
        v_severity := NEW.severity;
    END IF;
    NEW.prev_hash := v_prev;
    NEW.row_hash := cas_event_hash(v_prev, NEW.event_id, NEW.occurred_at, NEW.actor_user_id,
        NEW.actor_username, NEW.action, NEW.resource_type, NEW.resource_id, NEW.outcome,
        NEW.correlation_id, NEW.client_ip, NEW.details, v_severity);
    RETURN NEW;
END;
$$;

CREATE FUNCTION cas_reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_audit_events_chain BEFORE INSERT ON audit_events
    FOR EACH ROW EXECUTE FUNCTION cas_event_chain();
CREATE TRIGGER trg_audit_events_immutable BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION cas_reject_modification();
CREATE TRIGGER trg_audit_events_no_truncate BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION cas_reject_modification();

CREATE TRIGGER trg_security_events_chain BEFORE INSERT ON security_events
    FOR EACH ROW EXECUTE FUNCTION cas_event_chain();
CREATE TRIGGER trg_security_events_immutable BEFORE UPDATE OR DELETE ON security_events
    FOR EACH ROW EXECUTE FUNCTION cas_reject_modification();
CREATE TRIGGER trg_security_events_no_truncate BEFORE TRUNCATE ON security_events
    FOR EACH STATEMENT EXECUTE FUNCTION cas_reject_modification();

CREATE TABLE notification_outbox (
    id               UUID PRIMARY KEY,
    channel          VARCHAR(10)  NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    recipient        VARCHAR(254) NOT NULL,
    template         VARCHAR(80)  NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    sent_at          TIMESTAMPTZ,
    last_error       VARCHAR(500)
);
CREATE INDEX ix_outbox_pending ON notification_outbox (next_attempt_at) WHERE status = 'PENDING';
