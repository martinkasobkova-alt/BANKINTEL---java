-- Machine/API-key auth for external connectors (/api/connect/**), separate from user login JWTs.

CREATE TABLE api_keys (
    id           VARCHAR(36) PRIMARY KEY,
    user_id      VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key_hash     VARCHAR(64) NOT NULL,
    key_prefix   VARCHAR(32) NOT NULL,
    label        VARCHAR(200) NOT NULL DEFAULT '',
    scopes       JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_api_keys_key_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_user ON api_keys (user_id, created_at DESC);
