-- Homepage CMS and navigation sections (parity with Python MongoDB collections)

CREATE TABLE homepage_config (
    id                      VARCHAR(64) PRIMARY KEY,
    title                   VARCHAR(500) NOT NULL DEFAULT 'Exekutivní přehled',
    title_en                VARCHAR(500),
    subtitle                VARCHAR(500) NOT NULL DEFAULT 'Vámi vybraná data z veřejných portálů · ARAD ČNB a další',
    subtitle_en             VARCHAR(500),
    default_chart_type      VARCHAR(32) NOT NULL DEFAULT 'line',
    default_chart_frequency VARCHAR(8),
    headline_kpis           JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE homepage_widgets (
    id              VARCHAR(36) PRIMARY KEY,
    config_id       VARCHAR(64) NOT NULL REFERENCES homepage_config(id) ON DELETE CASCADE,
    widget_type     VARCHAR(64) NOT NULL,
    title           VARCHAR(255) NOT NULL DEFAULT '',
    title_en        VARCHAR(255),
    width           VARCHAR(32) NOT NULL DEFAULT 'full',
    row_span        INT,
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order      INT NOT NULL DEFAULT 0,
    data_snapshot   JSONB,
    last_fetched_at TIMESTAMPTZ,
    snapshot_status VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_homepage_widgets_config ON homepage_widgets(config_id, sort_order);

CREATE TABLE sections (
    id                      VARCHAR(36) PRIMARY KEY,
    slug                    VARCHAR(128) NOT NULL UNIQUE,
    name                    VARCHAR(255) NOT NULL,
    name_en                 VARCHAR(255),
    icon                    VARCHAR(64) NOT NULL DEFAULT 'Folder',
    subtitle                VARCHAR(500) NOT NULL DEFAULT '',
    subtitle_en             VARCHAR(500),
    sort_order              INT NOT NULL DEFAULT 0,
    default_chart_type      VARCHAR(32) NOT NULL DEFAULT 'line',
    default_chart_frequency VARCHAR(8),
    section_pages           JSONB NOT NULL DEFAULT '[]'::jsonb,
    headline_kpis           JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sections_sort_order ON sections(sort_order);

CREATE TABLE section_widgets (
    id              VARCHAR(36) PRIMARY KEY,
    section_id      VARCHAR(36) NOT NULL REFERENCES sections(id) ON DELETE CASCADE,
    section_page_id VARCHAR(36),
    widget_type     VARCHAR(64) NOT NULL,
    title           VARCHAR(255) NOT NULL DEFAULT '',
    title_en        VARCHAR(255),
    width           VARCHAR(32) NOT NULL DEFAULT 'full',
    row_span        INT,
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order      INT NOT NULL DEFAULT 0,
    data_snapshot   JSONB,
    last_fetched_at TIMESTAMPTZ,
    snapshot_status VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_section_widgets_section ON section_widgets(section_id, sort_order);

INSERT INTO homepage_config (id, title, subtitle)
VALUES ('main', 'Exekutivní přehled', 'Vámi vybraná data z veřejných portálů · ARAD ČNB a další');

INSERT INTO app_settings (id, settings_json)
VALUES ('global', '{"default_appearance_id":"blue"}'::jsonb)
ON CONFLICT (id) DO NOTHING;
