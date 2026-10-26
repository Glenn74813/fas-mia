-- Server-side session state for stateless JWT authentication.
-- Only SHA-256 hashes of refresh and reset tokens are stored; MFA secrets are AES-256-GCM ciphertext.

CREATE TABLE user_sessions (
    id                 UUID PRIMARY KEY,
    user_id            UUID         NOT NULL REFERENCES users (id),
    created_at         TIMESTAMPTZ  NOT NULL,
    last_refreshed_at  TIMESTAMPTZ  NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    revoked_at         TIMESTAMPTZ,
    revoke_reason      VARCHAR(40),
    client_ip          VARCHAR(45),
    user_agent         VARCHAR(300)
);
CREATE INDEX ix_user_sessions_user_active ON user_sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES user_sessions (id),
    token_hash      CHAR(64)    NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    replaced_by_id  UUID        REFERENCES refresh_tokens (id)
);
CREATE INDEX ix_refresh_tokens_session ON refresh_tokens (session_id);

CREATE TABLE mfa_methods (
    id                 UUID PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES users (id),
    method_type        VARCHAR(20) NOT NULL CHECK (method_type IN ('TOTP', 'EMAIL_OTP', 'SMS_OTP', 'WEBAUTHN')),
    secret_ciphertext  BYTEA,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    last_used_step     BIGINT,
    created_at         TIMESTAMPTZ NOT NULL,
    activated_at       TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ
);
-- At most one active and one pending method of each type per user.
CREATE UNIQUE INDEX uq_mfa_active_per_type ON mfa_methods (user_id, method_type) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_mfa_pending_per_type ON mfa_methods (user_id, method_type) WHERE status = 'PENDING';

CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id),
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);
CREATE INDEX ix_password_reset_user ON password_reset_tokens (user_id);
