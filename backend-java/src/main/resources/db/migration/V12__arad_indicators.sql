CREATE TABLE IF NOT EXISTS arad_indicators (
    id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL,
    indicator_id VARCHAR(64) NOT NULL,
    name VARCHAR(512) NOT NULL DEFAULT '',
    frequency_code VARCHAR(32) NOT NULL DEFAULT '',
    frequency_name VARCHAR(128) NOT NULL DEFAULT '',
    unit VARCHAR(128) NOT NULL DEFAULT '',
    unit_mult VARCHAR(128) NOT NULL DEFAULT '',
    fetched_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_arad_indicators_source_indicator UNIQUE (source_id, indicator_id)
);

CREATE INDEX IF NOT EXISTS idx_arad_indicators_source_id ON arad_indicators (source_id);
