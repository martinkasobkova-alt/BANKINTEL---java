-- Data sources, sync logs, datasets, and ingested records

CREATE TABLE sources (
    id                          VARCHAR(36) PRIMARY KEY,
    name                        VARCHAR(255) NOT NULL UNIQUE,
    source_type                 VARCHAR(64) NOT NULL,
    base_url                    TEXT NOT NULL DEFAULT '',
    endpoint                    TEXT NOT NULL DEFAULT '',
    method                      VARCHAR(16) NOT NULL DEFAULT 'GET',
    auth_type                   VARCHAR(32) NOT NULL DEFAULT 'none',
    refresh_interval_minutes    INT NOT NULL DEFAULT 60,
    active                      BOOLEAN NOT NULL DEFAULT TRUE,
    dataset_name                VARCHAR(255),
    last_sync_at                TIMESTAMPTZ,
    last_sync_started_at        TIMESTAMPTZ,
    last_sync_finished_at       TIMESTAMPTZ,
    last_sync_duration_ms       INT,
    last_sync_records_ingested  INT,
    last_sync_http_status       INT,
    last_sync_error             TEXT NOT NULL DEFAULT '',
    last_sync_status            VARCHAR(32),
    last_sync_message           TEXT NOT NULL DEFAULT '',
    last_sync_reason_code       VARCHAR(64),
    last_sync_response_preview  TEXT,
    last_sync_log_id            VARCHAR(36),
    sync_state                  VARCHAR(32),
    sync_queue_state            VARCHAR(32),
    sync_retry_after_sec        INT,
    sync_retry_at               TIMESTAMPTZ,
    connector_config            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sources_created_at ON sources (created_at DESC);
CREATE INDEX idx_sources_source_type ON sources (source_type);
CREATE INDEX idx_sources_last_sync_status ON sources (last_sync_status);

CREATE TABLE sync_logs (
    id                  VARCHAR(36) PRIMARY KEY,
    source_id           VARCHAR(36) NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    source_name         VARCHAR(255) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    records_ingested    INT NOT NULL DEFAULT 0,
    message             TEXT NOT NULL DEFAULT '',
    http_status         INT,
    duration_ms         INT,
    reason_code         VARCHAR(64),
    response_preview    TEXT
);

CREATE INDEX idx_sync_logs_source_id ON sync_logs (source_id);
CREATE INDEX idx_sync_logs_started_at ON sync_logs (started_at DESC);

CREATE TABLE datasets (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    source_id       VARCHAR(36) REFERENCES sources (id) ON DELETE SET NULL,
    source_name     VARCHAR(255),
    fields          JSONB NOT NULL DEFAULT '[]'::jsonb,
    record_count    INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_datasets_name ON datasets (name);

CREATE TABLE records (
    id              VARCHAR(36) PRIMARY KEY,
    dataset_id      VARCHAR(36) NOT NULL REFERENCES datasets (id) ON DELETE CASCADE,
    source_id       VARCHAR(36),
    dedupe_key      VARCHAR(512),
    data            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_records_dataset_id ON records (dataset_id);
CREATE INDEX idx_records_dataset_created ON records (dataset_id, created_at DESC);
CREATE INDEX idx_records_dedupe_key ON records (dedupe_key);
