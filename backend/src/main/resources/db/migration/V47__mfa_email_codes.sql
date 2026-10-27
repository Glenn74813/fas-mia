-- E-mail one-time codes as a second factor (TOR §4.2: "OTP via SMS/email"). A code belongs to one sign-in step (the
-- MFA token's id), lives at most as long as that step, is stored only as a hash, and allows five attempts.
CREATE TABLE mfa_email_codes (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id),
    challenge_id  VARCHAR(64)  NOT NULL,
    code_hash     VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    attempts      SMALLINT     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    used_at       TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX ix_mfa_email_codes_user ON mfa_email_codes (user_id, created_at DESC);
