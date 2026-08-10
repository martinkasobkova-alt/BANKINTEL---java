-- PDF magazine archive: titles, issues, fulltext chunks, chart links, on-disk files

CREATE TABLE stored_files (
    id              VARCHAR(36) PRIMARY KEY,
    storage_key     VARCHAR(512) NOT NULL,
    original_name   VARCHAR(512) NOT NULL DEFAULT '',
    content_type    VARCHAR(128) NOT NULL DEFAULT 'application/pdf',
    size_bytes      BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stored_files_key ON stored_files (storage_key);

CREATE TABLE magazines (
    id              VARCHAR(36) PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    slug            VARCHAR(128) NOT NULL UNIQUE,
    description     VARCHAR(4000) NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_magazines_title ON magazines (title);

CREATE TABLE magazine_issues (
    id              VARCHAR(36) PRIMARY KEY,
    magazine_id     VARCHAR(36) NOT NULL REFERENCES magazines(id) ON DELETE CASCADE,
    issue_label     VARCHAR(120) NOT NULL,
    title           VARCHAR(240) NOT NULL DEFAULT '',
    description     VARCHAR(4000) NOT NULL DEFAULT '',
    cover_image_url VARCHAR(2048),
    published_at    VARCHAR(80) NOT NULL DEFAULT '',
    original_name   VARCHAR(512) NOT NULL DEFAULT 'issue.pdf',
    stored_file_id  VARCHAR(36) REFERENCES stored_files(id) ON DELETE SET NULL,
    size_bytes      BIGINT NOT NULL DEFAULT 0,
    ingest_status   VARCHAR(32) NOT NULL DEFAULT 'pending',
    ingest_error    VARCHAR(500) NOT NULL DEFAULT '',
    page_count      INT NOT NULL DEFAULT 0,
    chunk_count     INT NOT NULL DEFAULT 0,
    ingested_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_magazine_issues_magazine ON magazine_issues (magazine_id, published_at DESC, created_at DESC);
CREATE INDEX idx_magazine_issues_ingest ON magazine_issues (ingest_status);

CREATE TABLE magazine_text_chunks (
    id              VARCHAR(36) PRIMARY KEY,
    issue_id        VARCHAR(36) NOT NULL REFERENCES magazine_issues(id) ON DELETE CASCADE,
    magazine_id     VARCHAR(36) NOT NULL REFERENCES magazines(id) ON DELETE CASCADE,
    page            INT NOT NULL,
    chunk_order     INT NOT NULL,
    text            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_magazine_chunks_issue ON magazine_text_chunks (issue_id, page, chunk_order);
CREATE INDEX idx_magazine_chunks_magazine ON magazine_text_chunks (magazine_id);

CREATE TABLE magazine_pdf_links (
    id              VARCHAR(36) PRIMARY KEY,
    issue_id        VARCHAR(36) NOT NULL REFERENCES magazine_issues(id) ON DELETE CASCADE,
    magazine_id     VARCHAR(36) NOT NULL REFERENCES magazines(id) ON DELETE CASCADE,
    page            INT NOT NULL,
    label           VARCHAR(240) NOT NULL,
    anchor_text     VARCHAR(2000) NOT NULL DEFAULT '',
    link_kind       VARCHAR(16) NOT NULL DEFAULT 'text',
    bbox_x0         DOUBLE PRECISION,
    bbox_y0         DOUBLE PRECISION,
    bbox_x1         DOUBLE PRECISION,
    bbox_y1         DOUBLE PRECISION,
    target_kind     VARCHAR(16) NOT NULL DEFAULT 'chart',
    target_title    VARCHAR(240) NOT NULL DEFAULT '',
    source_type     VARCHAR(80) NOT NULL DEFAULT '',
    set_id          VARCHAR(240) NOT NULL DEFAULT '',
    link_url        VARCHAR(4000) NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_magazine_pdf_links_issue ON magazine_pdf_links (issue_id, page, created_at);
