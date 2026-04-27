-- TrashTalk initial schema
-- Phase 0: tabulky pro auth, servery, kanály, zprávy, soubory, voice sessions, audit log.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =========================================================
-- Users
-- =========================================================
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(32) NOT NULL UNIQUE,
    email           VARCHAR(254) NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,                     -- Argon2id encoded string
    display_name    VARCHAR(64),
    avatar_url      TEXT,
    public_key_pem  TEXT,                              -- volitelné pro budoucí E2EE / per-user PKI
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_username_lower ON users (LOWER(username));
CREATE INDEX idx_users_email_lower    ON users (LOWER(email));

-- =========================================================
-- Refresh tokens (JWT access tokeny stateless, refresh trackujeme)
-- =========================================================
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,              -- SHA-256 hex hash, ne raw token
    device_info     TEXT,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- =========================================================
-- Servers (Discord guild)
-- =========================================================
CREATE TABLE servers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(100) NOT NULL,
    owner_id            UUID NOT NULL REFERENCES users(id),
    icon_url            TEXT,
    storage_used_bytes  BIGINT NOT NULL DEFAULT 0 CHECK (storage_used_bytes >= 0),
    storage_limit_bytes BIGINT NOT NULL DEFAULT 1073741824, -- 1 GiB default
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_servers_owner_id ON servers (owner_id);

-- =========================================================
-- Server members + role
-- =========================================================
CREATE TYPE server_role AS ENUM ('OWNER', 'ADMINISTRATOR', 'VISITOR');

CREATE TABLE server_members (
    server_id   UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        server_role NOT NULL DEFAULT 'VISITOR',
    nickname    VARCHAR(64),
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (server_id, user_id)
);

CREATE INDEX idx_server_members_user_id ON server_members (user_id);

-- =========================================================
-- Channels (text + voice)
-- =========================================================
CREATE TYPE channel_type AS ENUM ('TEXT', 'VOICE');

CREATE TABLE channels (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_id           UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    name                VARCHAR(100) NOT NULL,
    type                channel_type NOT NULL,
    position            INTEGER NOT NULL DEFAULT 0,
    topic               TEXT,
    voice_bitrate_kbps  INTEGER,                       -- jen pro VOICE, NULL pro TEXT
    permissions_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_channels_server_id ON channels (server_id, position);

-- =========================================================
-- Messages (text chat)
-- =========================================================
CREATE TABLE messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id      UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    author_id       UUID NOT NULL REFERENCES users(id),
    content         TEXT NOT NULL,
    reply_to_id     UUID REFERENCES messages(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at       TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_messages_channel_created ON messages (channel_id, created_at DESC);
CREATE INDEX idx_messages_author_id       ON messages (author_id);

-- =========================================================
-- Attachments (přiložené soubory ve zprávách)
-- =========================================================
CREATE TABLE attachments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id      UUID REFERENCES messages(id) ON DELETE CASCADE,
    server_id       UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    uploader_id     UUID NOT NULL REFERENCES users(id),
    filename        TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL CHECK (size_bytes > 0),
    mime_type       VARCHAR(255),
    storage_key     TEXT NOT NULL,                     -- klíč v MinIO (server_id/uuid)
    sha256_hex      VARCHAR(64) NOT NULL,
    upload_complete BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_attachments_server_id ON attachments (server_id);
CREATE INDEX idx_attachments_message_id ON attachments (message_id);

-- =========================================================
-- Voice sessions (kdo právě sedí v jakém voice kanálu)
-- =========================================================
CREATE TABLE voice_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id          UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_session_id    TEXT NOT NULL,
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at             TIMESTAMPTZ,
    UNIQUE (channel_id, user_id, joined_at)
);

CREATE INDEX idx_voice_sessions_channel_active
    ON voice_sessions (channel_id) WHERE left_at IS NULL;

-- =========================================================
-- Invites
-- =========================================================
CREATE TABLE invites (
    code            VARCHAR(16) PRIMARY KEY,
    server_id       UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    creator_id      UUID NOT NULL REFERENCES users(id),
    max_uses        INTEGER,                           -- NULL = unlimited
    uses            INTEGER NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,                       -- NULL = never
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invites_server_id ON invites (server_id);

-- =========================================================
-- Audit log (změny rolí, mazání zpráv, ban)
-- =========================================================
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    server_id       UUID REFERENCES servers(id) ON DELETE SET NULL,
    actor_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    action          VARCHAR(64) NOT NULL,              -- ROLE_CHANGE, MESSAGE_DELETE, BAN, ...
    target_type     VARCHAR(32),                       -- USER, MESSAGE, CHANNEL, ...
    target_id       TEXT,
    metadata_json   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_server_created ON audit_log (server_id, created_at DESC);
