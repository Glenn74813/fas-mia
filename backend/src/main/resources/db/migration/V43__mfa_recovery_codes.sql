-- One-time recovery codes for the authenticator (MS §9.2): shown once when an authenticator is enrolled, stored only
-- as Argon2 hashes. A code is spent (used_at) when it replaces an authenticator code at sign-in, and every unused code
-- is withdrawn (revoked_at) when a new set is issued or an administrator resets the user's MFA. Rows are kept for audit.
CREATE TABLE mfa_recovery_codes (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id),
    code_hash   VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    CONSTRAINT ck_recovery_code_spent_once CHECK (used_at IS NULL OR revoked_at IS NULL)
);
CREATE INDEX ix_mfa_recovery_codes_unused ON mfa_recovery_codes (user_id) WHERE used_at IS NULL AND revoked_at IS NULL;
