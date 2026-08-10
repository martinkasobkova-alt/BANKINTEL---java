-- User chat: conversations, participants, messages, attachment metadata

CREATE TABLE chat_conversations (
    id                  VARCHAR(36) PRIMARY KEY,
    type                VARCHAR(16) NOT NULL DEFAULT 'group',
    title               VARCHAR(160),
    created_by          VARCHAR(36) NOT NULL REFERENCES users(id),
    last_message_preview VARCHAR(220) NOT NULL DEFAULT '',
    last_message_at     TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_conversations_updated ON chat_conversations(updated_at DESC, created_at DESC);

CREATE TABLE chat_participants (
    conversation_id     VARCHAR(36) NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    user_id             VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_read_at        TIMESTAMPTZ,
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_chat_participants_user ON chat_participants(user_id);

CREATE TABLE chat_messages (
    id                  VARCHAR(36) PRIMARY KEY,
    conversation_id     VARCHAR(36) NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    sender_id           VARCHAR(36) NOT NULL REFERENCES users(id),
    text                TEXT NOT NULL DEFAULT '',
    attachment_ids      JSONB NOT NULL DEFAULT '[]'::jsonb,
    shared_chart        JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_conv_created ON chat_messages(conversation_id, created_at DESC);

CREATE TABLE chat_attachments (
    id                  VARCHAR(36) PRIMARY KEY,
    conversation_id     VARCHAR(36) NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    uploader_id         VARCHAR(36) NOT NULL REFERENCES users(id),
    file_name           VARCHAR(180) NOT NULL,
    content_type        VARCHAR(180) NOT NULL DEFAULT 'application/octet-stream',
    size                BIGINT NOT NULL,
    storage_path        VARCHAR(512) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_attachments_conv ON chat_attachments(conversation_id, created_at DESC);
