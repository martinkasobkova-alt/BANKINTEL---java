-- User file uploads (shared by /api/me/uploads and /api/user-data), parsed company series, saved my-series

CREATE TABLE user_uploads (
    id                      VARCHAR(36) PRIMARY KEY,
    user_id                 VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    company_id              VARCHAR(64),
    original_name           VARCHAR(512) NOT NULL,
    filename                VARCHAR(512),
    file_type               VARCHAR(32),
    mime_type               VARCHAR(128),
    status                  VARCHAR(64) NOT NULL DEFAULT 'uploaded',
    stored_rel_path         VARCHAR(512) NOT NULL,
    size_bytes              BIGINT NOT NULL DEFAULT 0,
    detected_tables         JSONB NOT NULL DEFAULT '[]'::jsonb,
    mapped_series_count     INT NOT NULL DEFAULT 0,
    extracted_text_preview  TEXT NOT NULL DEFAULT '',
    errors                  JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_uploads_user_created ON user_uploads (user_id, created_at DESC);
CREATE INDEX idx_user_uploads_user_company ON user_uploads (user_id, company_id, created_at DESC);

CREATE TABLE user_uploaded_series (
    id                  VARCHAR(36) PRIMARY KEY,
    user_id             VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    upload_id           VARCHAR(36) NOT NULL REFERENCES user_uploads(id) ON DELETE CASCADE,
    company_id          VARCHAR(64),
    dataset_id          VARCHAR(36) NOT NULL,
    title               VARCHAR(320) NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    metric_type         VARCHAR(64) NOT NULL DEFAULT 'other',
    unit                VARCHAR(64),
    currency            VARCHAR(32),
    frequency           VARCHAR(16) NOT NULL DEFAULT 'unknown',
    sector_id           VARCHAR(64),
    geo                 VARCHAR(64),
    detected_domain     VARCHAR(128),
    detected_domains    JSONB NOT NULL DEFAULT '[]'::jsonb,
    tags                JSONB NOT NULL DEFAULT '[]'::jsonb,
    observations        JSONB NOT NULL DEFAULT '[]'::jsonb,
    periods             JSONB NOT NULL DEFAULT '[]'::jsonb,
    mapping_confidence  DOUBLE PRECISION NOT NULL DEFAULT 0,
    mapping_reason      TEXT NOT NULL DEFAULT '',
    is_private          BOOLEAN NOT NULL DEFAULT TRUE,
    priority            VARCHAR(16) NOT NULL DEFAULT 'high',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_uploaded_series_user ON user_uploaded_series (user_id, upload_id);
CREATE INDEX idx_user_uploaded_series_user_metric ON user_uploaded_series (user_id, company_id, metric_type);

CREATE TABLE user_saved_series (
    id                  VARCHAR(36) PRIMARY KEY,
    user_id             VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title               VARCHAR(500) NOT NULL,
    source              VARCHAR(200) NOT NULL DEFAULT '',
    source_type         VARCHAR(80) NOT NULL DEFAULT '',
    source_series_id    VARCHAR(500) NOT NULL DEFAULT '',
    source_dataset_id   VARCHAR(120) NOT NULL DEFAULT '',
    resolver_payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    unit                VARCHAR(120) NOT NULL DEFAULT '',
    frequency           VARCHAR(80) NOT NULL DEFAULT '',
    area                VARCHAR(200) NOT NULL DEFAULT '',
    category            VARCHAR(500) NOT NULL DEFAULT '',
    start_period        VARCHAR(64) NOT NULL DEFAULT '',
    end_period          VARCHAR(64) NOT NULL DEFAULT '',
    last_period         VARCHAR(64) NOT NULL DEFAULT '',
    last_value          DOUBLE PRECISION,
    point_count         INT NOT NULL DEFAULT 0,
    data_points         JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_saved_series_user_updated ON user_saved_series (user_id, updated_at DESC);
