-- The actor's roles at the time of each audit and security event (MS §10). The roles join the hash chain only when
-- they are recorded: rows written before this migration hash exactly as before and still verify, and every later
-- row's hash also covers its roles. The tables stay append-only (V3 triggers).

ALTER TABLE audit_events ADD COLUMN actor_roles VARCHAR(500);
ALTER TABLE security_events ADD COLUMN actor_roles VARCHAR(500);

CREATE FUNCTION cas_event_hash(
    p_prev_hash TEXT, p_event_id UUID, p_occurred_at TIMESTAMPTZ, p_actor_user_id UUID,
    p_actor_username TEXT, p_action TEXT, p_resource_type TEXT, p_resource_id TEXT,
    p_outcome TEXT, p_correlation_id TEXT, p_client_ip TEXT, p_details JSONB, p_severity TEXT, p_actor_roles TEXT)
RETURNS CHAR(64)
LANGUAGE sql IMMUTABLE AS $$
    SELECT encode(sha256(convert_to(
        p_prev_hash || '|' || p_event_id::text || '|' ||
        to_char(p_occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|' ||
        coalesce(p_actor_user_id::text, '') || '|' || coalesce(p_actor_username, '') || '|' ||
        p_action || '|' || p_resource_type || '|' || coalesce(p_resource_id, '') || '|' ||
        p_outcome || '|' || coalesce(p_correlation_id, '') || '|' || coalesce(p_client_ip, '') || '|' ||
        p_details::text || '|' || coalesce(p_severity, '') ||
        CASE WHEN p_actor_roles IS NULL THEN '' ELSE '|roles:' || p_actor_roles END,
        'UTF8')), 'hex')
$$;

CREATE OR REPLACE FUNCTION cas_event_chain() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_prev TEXT;
    v_severity TEXT;
BEGIN
    -- As V3: serialise inserts per table so the chain is strictly linear, and assign the id under the lock.
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
        NEW.correlation_id, NEW.client_ip, NEW.details, v_severity, NEW.actor_roles);
    RETURN NEW;
END;
$$;
