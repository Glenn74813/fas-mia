-- Phase 4d: support tickets (incidents, service requests, change requests, enhancements) with SLA
-- policies held as configuration per contract phase (C-15), a holiday calendar for business-hours
-- clocks, and an append-only event history per ticket.

CREATE TABLE sla_policies (
    id                  UUID PRIMARY KEY,
    contract_phase      VARCHAR(30)  NOT NULL,
    ticket_type         VARCHAR(16)  NOT NULL CHECK (ticket_type IN ('INCIDENT', 'SERVICE_REQUEST', 'CHANGE_REQUEST', 'ENHANCEMENT')),
    priority            VARCHAR(2)   NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    response_minutes    INTEGER      NOT NULL CHECK (response_minutes > 0),
    resolution_minutes  INTEGER      NOT NULL CHECK (resolution_minutes > 0),
    business_hours      BOOLEAN      NOT NULL,
    placeholder         BOOLEAN      NOT NULL DEFAULT FALSE,
    status              VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_by          VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_sla_order CHECK (resolution_minutes >= response_minutes)
);
CREATE UNIQUE INDEX uq_sla_policy_active ON sla_policies (contract_phase, ticket_type, priority) WHERE status = 'ACTIVE';

CREATE TABLE business_holidays (
    holiday_date  DATE         PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    created_by    VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE tickets (
    id                    UUID PRIMARY KEY,
    ticket_number         VARCHAR(30)   NOT NULL UNIQUE,
    ticket_type           VARCHAR(16)   NOT NULL CHECK (ticket_type IN ('INCIDENT', 'SERVICE_REQUEST', 'CHANGE_REQUEST', 'ENHANCEMENT')),
    priority              VARCHAR(2)    NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    title                 VARCHAR(200)  NOT NULL,
    description           VARCHAR(4000) NOT NULL,
    module                VARCHAR(40),
    status                VARCHAR(18)   NOT NULL CHECK (status IN ('NEW', 'ACKNOWLEDGED', 'PENDING_APPROVAL', 'APPROVED', 'IN_PROGRESS',
                                                                  'ON_HOLD', 'RESOLVED', 'CLOSED', 'REJECTED', 'CANCELLED')),
    reporter_id           UUID          NOT NULL REFERENCES users (id),
    reporter              VARCHAR(64)   NOT NULL,
    assignee_id           UUID          REFERENCES users (id),
    assignee              VARCHAR(64),
    contract_phase        VARCHAR(30)   NOT NULL,
    sla_policy_id         UUID          REFERENCES sla_policies (id),
    response_due_at       TIMESTAMPTZ,
    resolution_due_at     TIMESTAMPTZ,
    first_response_at     TIMESTAMPTZ,
    on_hold_since         TIMESTAMPTZ,
    status_before_hold    VARCHAR(18),
    resolved_at           TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    response_breached     BOOLEAN       NOT NULL DEFAULT FALSE,
    resolution_breached   BOOLEAN       NOT NULL DEFAULT FALSE,
    at_risk_notified      BOOLEAN       NOT NULL DEFAULT FALSE,
    escalation_level      SMALLINT      NOT NULL DEFAULT 0,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_tickets_open ON tickets (status, resolution_due_at) WHERE status NOT IN ('RESOLVED', 'CLOSED', 'REJECTED', 'CANCELLED');
CREATE INDEX ix_tickets_reporter ON tickets (reporter_id, created_at DESC);

CREATE TABLE ticket_events (
    ticket_id    UUID          NOT NULL REFERENCES tickets (id),
    seq          INTEGER       NOT NULL,
    occurred_at  TIMESTAMPTZ   NOT NULL,
    actor        VARCHAR(64)   NOT NULL,
    event_type   VARCHAR(20)   NOT NULL,
    from_value   VARCHAR(60),
    to_value     VARCHAR(60),
    comment      VARCHAR(4000),
    internal     BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (ticket_id, seq)
);

CREATE FUNCTION ticket_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ticket history is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_ticket_events_immutable BEFORE UPDATE OR DELETE ON ticket_events
    FOR EACH ROW EXECUTE FUNCTION ticket_events_immutable();
