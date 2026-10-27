-- Third-party access (TOR §4.6, Annex A.4). API clients sign in with the OAuth 2.0 client-credentials grant and get
-- short-lived tokens limited to read-only scopes; their secrets are stored as Argon2id hashes, can be rotated and
-- revoked, and every client has its own rate limit. Webhooks tell subscribers that something happened (an event
-- taken from the audit trail, never the business data itself), signed with HMAC-SHA-256, retried with back-off and
-- logged per delivery.
CREATE TABLE api_clients (
    id                 UUID PRIMARY KEY,
    client_id          VARCHAR(40)    NOT NULL UNIQUE,
    name               VARCHAR(120)   NOT NULL,
    description        VARCHAR(500),
    scopes             VARCHAR(40)[]  NOT NULL,
    secret_hash        VARCHAR(200)   NOT NULL,
    secret_rotated_at  TIMESTAMPTZ    NOT NULL,
    status             VARCHAR(10)    NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    expires_at         TIMESTAMPTZ,
    last_used_at       TIMESTAMPTZ,
    created_by_id      UUID           NOT NULL REFERENCES users (id),
    created_by         VARCHAR(64)    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL,
    revoked_by         VARCHAR(64),
    revoked_at         TIMESTAMPTZ,
    revoke_reason      VARCHAR(500),
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_api_client_scopes CHECK (cardinality(scopes) > 0),
    CONSTRAINT ck_api_client_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE TABLE webhook_subscriptions (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(120)   NOT NULL,
    url                   VARCHAR(500)   NOT NULL,
    events                VARCHAR(60)[]  NOT NULL,
    -- AES-256-GCM, bound to the row id; shown once when created or rotated.
    secret_enc            BYTEA          NOT NULL,
    status                VARCHAR(10)    NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'DISABLED')),
    consecutive_failures  INT            NOT NULL DEFAULT 0,
    created_by_id         UUID           NOT NULL REFERENCES users (id),
    created_by            VARCHAR(64)    NOT NULL,
    created_at            TIMESTAMPTZ    NOT NULL,
    updated_by            VARCHAR(64)    NOT NULL,
    updated_at            TIMESTAMPTZ    NOT NULL,
    version               BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_webhook_events CHECK (cardinality(events) > 0)
);

CREATE TABLE webhook_deliveries (
    id               UUID PRIMARY KEY,
    subscription_id  UUID           NOT NULL REFERENCES webhook_subscriptions (id),
    event_id         UUID           NOT NULL,
    event_type       VARCHAR(60)    NOT NULL,
    payload          TEXT           NOT NULL,
    status           VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts         INT            NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_status      INT,
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ    NOT NULL,
    delivered_at     TIMESTAMPTZ,
    CONSTRAINT uq_webhook_delivery UNIQUE (subscription_id, event_id)
);
CREATE INDEX ix_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_webhook_deliveries_subscription ON webhook_deliveries (subscription_id, created_at DESC);

-- How far through the audit trail the webhook dispatcher has read (one row).
CREATE TABLE webhook_cursor (
    id             SMALLINT PRIMARY KEY CHECK (id = 1),
    last_audit_id  BIGINT   NOT NULL
);
INSERT INTO webhook_cursor (id, last_audit_id) SELECT 1, coalesce(max(id), 0) FROM audit_events;

INSERT INTO permissions (code, description, category, financial) VALUES
    ('API_CLIENT_MANAGE', 'Create, rotate and revoke API clients and manage webhook subscriptions', 'INTEGRATION', FALSE);
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'API_CLIENT_MANAGE' FROM roles r WHERE r.code = 'CHIEF_ACCOUNTANT'
ON CONFLICT DO NOTHING;
