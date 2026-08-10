-- Core relational schema (PostgreSQL primary DB)

CREATE TABLE users (
    id              VARCHAR(36) PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    company         VARCHAR(255),
    phone           VARCHAR(64),
    role            VARCHAR(32) NOT NULL DEFAULT 'viewer',
    access_tier     VARCHAR(32) NOT NULL DEFAULT 'free',
    has_premium_access BOOLEAN NOT NULL DEFAULT FALSE,
    premium_access_granted_at TIMESTAMPTZ,
    premium_access_source VARCHAR(64),
    password_hash   VARCHAR(255) NOT NULL,
    email_verified  BOOLEAN NOT NULL DEFAULT TRUE,
    email_verified_at TIMESTAMPTZ,
    email_verification_token_hash VARCHAR(128),
    email_verification_expires_at TIMESTAMPTZ,
    password_reset_token_hash VARCHAR(128),
    password_reset_expires_at TIMESTAMPTZ,
    open_personal_dashboard_on_login BOOLEAN NOT NULL DEFAULT FALSE,
    default_dashboard_page_id VARCHAR(36),
    admin_nav_order JSONB,
    user_nav_order JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email_lower ON users (LOWER(email));

CREATE TABLE user_dashboard_pages (
    id              VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    slug            VARCHAR(128) NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    access_mode     VARCHAR(32) NOT NULL DEFAULT 'owner_only',
    allowed_user_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    share_token     VARCHAR(96),
    share_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, slug)
);

CREATE INDEX idx_dashboard_pages_user ON user_dashboard_pages(user_id, sort_order);

CREATE TABLE user_dashboard_widgets (
    id              VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    page_id         VARCHAR(36) NOT NULL REFERENCES user_dashboard_pages(id) ON DELETE CASCADE,
    widget_type     VARCHAR(64) NOT NULL,
    title           VARCHAR(255) NOT NULL DEFAULT '',
    description     TEXT NOT NULL DEFAULT '',
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    width           VARCHAR(32) NOT NULL DEFAULT 'full',
    row_span        INT,
    sort_order      INT NOT NULL DEFAULT 0,
    data_snapshot   JSONB,
    last_fetched_at TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    cache_key       VARCHAR(255),
    snapshot_status VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dashboard_widgets_page ON user_dashboard_widgets(page_id, sort_order);

CREATE TABLE feature_access_rules (
    feature_key     VARCHAR(64) PRIMARY KEY,
    label           VARCHAR(255) NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    access_level    VARCHAR(32) NOT NULL DEFAULT 'subscriber',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE app_settings (
    id              VARCHAR(64) PRIMARY KEY,
    subscriber_registration_code_hash VARCHAR(255),
    subscriber_code_updated_at TIMESTAMPTZ,
    subscriber_code_updated_by VARCHAR(36),
    settings_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO app_settings (id) VALUES ('app_config');
