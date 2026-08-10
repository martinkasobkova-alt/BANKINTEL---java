-- Formulas, computed indicators, audit logs, and bug reports

CREATE TABLE formulas (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    expression      TEXT NOT NULL,
    group_by        JSONB NOT NULL DEFAULT '["date"]'::jsonb,
    datasets        JSONB NOT NULL DEFAULT '[]'::jsonb,
    description     TEXT NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_formulas_created_at ON formulas (created_at DESC);

CREATE TABLE computed_indicators (
    id                  VARCHAR(36) PRIMARY KEY,
    name                VARCHAR(255) NOT NULL UNIQUE,
    operation           VARCHAR(64) NOT NULL,
    left_ref            JSONB NOT NULL DEFAULT '{}'::jsonb,
    right_ref           JSONB NOT NULL DEFAULT '{}'::jsonb,
    series              JSONB NOT NULL DEFAULT '[]'::jsonb,
    description         TEXT NOT NULL DEFAULT '',
    unit                VARCHAR(128) NOT NULL DEFAULT '',
    options             JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by_user_id  VARCHAR(36) REFERENCES users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ
);

CREATE INDEX idx_computed_indicators_created_at ON computed_indicators (created_at DESC);
CREATE INDEX idx_computed_indicators_created_by ON computed_indicators (created_by_user_id);

CREATE TABLE audit_logs (
    id              VARCHAR(36) PRIMARY KEY,
    action          VARCHAR(128) NOT NULL,
    actor_user_id   VARCHAR(36),
    actor_email     VARCHAR(320),
    target_type     VARCHAR(64) NOT NULL,
    target_id       VARCHAR(200) NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip              VARCHAR(100),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs (action);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_user_id);

CREATE TABLE bug_reports (
    id              VARCHAR(36) PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    description     TEXT NOT NULL,
    contact_email   VARCHAR(320),
    page_url        VARCHAR(1000),
    user_agent      VARCHAR(1000),
    viewport        VARCHAR(200),
    route           VARCHAR(500),
    user_id         VARCHAR(36),
    user_email      VARCHAR(320),
    user_role       VARCHAR(32),
    status          VARCHAR(16) NOT NULL DEFAULT 'open',
    priority        VARCHAR(16) NOT NULL DEFAULT 'medium',
    screenshot      JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     JSONB
);

CREATE INDEX idx_bug_reports_status_created ON bug_reports (status, created_at DESC);
CREATE INDEX idx_bug_reports_created_at ON bug_reports (created_at DESC);
