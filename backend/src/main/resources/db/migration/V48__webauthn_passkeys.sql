-- Passkeys (WebAuthn) as a second factor (TOR §4.2: "biometric authentication (if applicable)"). A user may register
-- several; each keeps its public key (in the attested credential data), signature counter and flags. Challenges are
-- single use and short-lived; an authentication challenge is bound to the sign-in step (MFA token id).
CREATE TABLE webauthn_credentials (
    id                        UUID PRIMARY KEY,
    user_id                   UUID         NOT NULL REFERENCES users (id),
    credential_id             BYTEA        NOT NULL UNIQUE,
    attested_credential_data  BYTEA        NOT NULL,
    sign_count                BIGINT       NOT NULL DEFAULT 0 CHECK (sign_count >= 0),
    uv_initialized            BOOLEAN      NOT NULL,
    backup_eligible           BOOLEAN,
    backup_state              BOOLEAN,
    name                      VARCHAR(100) NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL,
    last_used_at              TIMESTAMPTZ,
    revoked_at                TIMESTAMPTZ
);
CREATE INDEX ix_webauthn_credentials_user ON webauthn_credentials (user_id) WHERE revoked_at IS NULL;

CREATE TABLE webauthn_challenges (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id),
    purpose     VARCHAR(12)  NOT NULL CHECK (purpose IN ('REGISTER', 'AUTHENTICATE')),
    challenge   BYTEA        NOT NULL,
    bound_to    VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ
);
CREATE INDEX ix_webauthn_challenges_user ON webauthn_challenges (user_id, created_at DESC);
