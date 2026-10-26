-- Organization structure and identity / access management core tables.
-- All timestamps are TIMESTAMPTZ (stored UTC); identifiers exposed via the API are UUIDs.

CREATE TABLE organizations (
    id          UUID PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    tin         VARCHAR(20),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(64)  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  VARCHAR(64)  NOT NULL
);

CREATE TABLE offices (
    id               UUID PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations (id),
    code             VARCHAR(20)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       VARCHAR(64)  NOT NULL,
    CONSTRAINT uq_offices_org_code UNIQUE (organization_id, code)
);

CREATE TABLE departments (
    id          UUID PRIMARY KEY,
    office_id   UUID         NOT NULL REFERENCES offices (id),
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(64)  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  VARCHAR(64)  NOT NULL,
    CONSTRAINT uq_departments_office_code UNIQUE (office_id, code)
);

CREATE TABLE users (
    id                    UUID PRIMARY KEY,
    username              VARCHAR(64)  NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9._-]{3,64}$'),
    email                 VARCHAR(254) NOT NULL,
    display_name          VARCHAR(200) NOT NULL,
    user_type             VARCHAR(20)  NOT NULL CHECK (user_type IN ('INTERNAL', 'CONCESSIONAIRE', 'EXTERNAL_AUDITOR')),
    status                VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    password_hash         VARCHAR(255) NOT NULL,
    password_changed_at   TIMESTAMPTZ  NOT NULL,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_count    INT          NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until          TIMESTAMPTZ,
    access_expires_at     TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            VARCHAR(64)  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by            VARCHAR(64)  NOT NULL
);
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
CREATE INDEX ix_users_status ON users (status);

CREATE TABLE permissions (
    code         VARCHAR(64)  PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    description  VARCHAR(300) NOT NULL,
    category     VARCHAR(40)  NOT NULL,
    financial    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE roles (
    id           UUID PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    name         VARCHAR(120) NOT NULL,
    description  VARCHAR(500),
    system_role  BOOLEAN      NOT NULL DEFAULT FALSE,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(64)  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(64)  NOT NULL
);

CREATE TABLE role_permissions (
    role_id          UUID        NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_code  VARCHAR(64) NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE user_roles (
    user_id     UUID        NOT NULL REFERENCES users (id),
    role_id     UUID        NOT NULL REFERENCES roles (id),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by  VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX ix_user_roles_role ON user_roles (role_id);

CREATE TABLE sod_conflict_rules (
    id            UUID PRIMARY KEY,
    permission_a  VARCHAR(64)  NOT NULL REFERENCES permissions (code),
    permission_b  VARCHAR(64)  NOT NULL REFERENCES permissions (code),
    severity      VARCHAR(10)  NOT NULL CHECK (severity IN ('BLOCK', 'WARN')),
    rationale     VARCHAR(500) NOT NULL,
    CONSTRAINT ck_sod_distinct CHECK (permission_a <> permission_b),
    CONSTRAINT uq_sod_pair UNIQUE (permission_a, permission_b)
);

CREATE TABLE user_office_assignments (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id),
    office_id       UUID        NOT NULL REFERENCES offices (id),
    department_id   UUID        REFERENCES departments (id),
    effective_from  DATE        NOT NULL,
    effective_to    DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(64) NOT NULL,
    CONSTRAINT ck_uoa_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX ix_uoa_user ON user_office_assignments (user_id);

CREATE TABLE access_reviews (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id),
    reviewer_id  UUID        REFERENCES users (id),
    review_due   DATE        NOT NULL,
    reviewed_at  TIMESTAMPTZ,
    outcome      VARCHAR(20) CHECK (outcome IN ('RETAIN', 'MODIFY', 'REVOKE')),
    notes        VARCHAR(1000),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_access_review_not_self CHECK (reviewer_id IS NULL OR reviewer_id <> user_id)
);
CREATE INDEX ix_access_reviews_due ON access_reviews (review_due) WHERE reviewed_at IS NULL;
