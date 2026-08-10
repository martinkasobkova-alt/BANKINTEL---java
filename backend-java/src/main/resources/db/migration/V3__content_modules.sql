-- Content modules: articles, RSS, podcasts

CREATE TABLE article_categories (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    slug            VARCHAR(128) NOT NULL UNIQUE,
    sort_order      INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_article_categories_sort ON article_categories (sort_order, name);

CREATE TABLE articles (
    id              VARCHAR(36) PRIMARY KEY,
    slug            VARCHAR(128) NOT NULL UNIQUE,
    title           VARCHAR(240) NOT NULL,
    summary         VARCHAR(600) NOT NULL DEFAULT '',
    body            TEXT NOT NULL,
    cover_image_url VARCHAR(2048),
    category_id     VARCHAR(36) REFERENCES article_categories(id) ON DELETE SET NULL,
    published       BOOLEAN NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ,
    author_id       VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL,
    author_name     VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_articles_published ON articles (published, published_at DESC NULLS LAST, created_at DESC);
CREATE INDEX idx_articles_category ON articles (category_id);

CREATE TABLE rss_feeds (
    id                          VARCHAR(36) PRIMARY KEY,
    owner_user_id               VARCHAR(36) REFERENCES users(id) ON DELETE CASCADE,
    scope                       VARCHAR(16) NOT NULL DEFAULT 'global',
    name                        VARCHAR(500) NOT NULL,
    url                         VARCHAR(4000) NOT NULL,
    source_type                 VARCHAR(32) NOT NULL DEFAULT 'rss',
    category                    VARCHAR(200) NOT NULL DEFAULT '',
    enabled                     BOOLEAN NOT NULL DEFAULT TRUE,
    refresh_interval_minutes    INT NOT NULL DEFAULT 60,
    auto_translate              BOOLEAN NOT NULL DEFAULT FALSE,
    publish_to_articles         BOOLEAN NOT NULL DEFAULT FALSE,
    last_sync_at                TIMESTAMPTZ,
    last_sync_status            VARCHAR(32),
    last_sync_message           TEXT NOT NULL DEFAULT '',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rss_feeds_scope ON rss_feeds (scope, enabled, created_at DESC);
CREATE INDEX idx_rss_feeds_owner ON rss_feeds (owner_user_id, created_at DESC);

CREATE TABLE rss_items (
    id              VARCHAR(36) PRIMARY KEY,
    feed_id         VARCHAR(36) NOT NULL REFERENCES rss_feeds(id) ON DELETE CASCADE,
    owner_user_id   VARCHAR(36),
    title           VARCHAR(2000) NOT NULL DEFAULT '',
    summary         TEXT NOT NULL DEFAULT '',
    link            VARCHAR(4000) NOT NULL DEFAULT '',
    guid            VARCHAR(2000) NOT NULL DEFAULT '',
    author          VARCHAR(500) NOT NULL DEFAULT '',
    source_name     VARCHAR(500) NOT NULL DEFAULT '',
    category        VARCHAR(200) NOT NULL DEFAULT '',
    title_cs        VARCHAR(2000),
    summary_cs      TEXT,
    draft_article_id VARCHAR(36),
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rss_items_feed_published ON rss_items (feed_id, published_at DESC NULLS LAST);
CREATE INDEX idx_rss_items_category ON rss_items (category);

CREATE TABLE podcast_shows (
    id              VARCHAR(36) PRIMARY KEY,
    title           VARCHAR(500) NOT NULL,
    description     VARCHAR(2000) NOT NULL DEFAULT '',
    sort_order      INT NOT NULL DEFAULT 0,
    created_by      VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_podcast_shows_sort ON podcast_shows (sort_order, title);

CREATE TABLE podcast_episodes (
    id              VARCHAR(36) PRIMARY KEY,
    show_id         VARCHAR(36) REFERENCES podcast_shows(id) ON DELETE SET NULL,
    user_id         VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL,
    title           VARCHAR(2000) NOT NULL,
    summary         VARCHAR(800) NOT NULL DEFAULT '',
    audio_url       VARCHAR(4000),
    external_url    VARCHAR(4000),
    cover_image_url VARCHAR(4000),
    feed_title      VARCHAR(500),
    author          VARCHAR(500),
    gridfs_id       VARCHAR(64),
    source          VARCHAR(32) NOT NULL DEFAULT 'upload',
    published       BOOLEAN NOT NULL DEFAULT TRUE,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_podcast_episodes_show ON podcast_episodes (show_id, published_at DESC NULLS LAST);
CREATE INDEX idx_podcast_episodes_published ON podcast_episodes (published, published_at DESC NULLS LAST);
