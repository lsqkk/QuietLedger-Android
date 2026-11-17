-- QuietLedger (打扰税) database schema.
-- Executed idempotently by app.py at startup (init_db).  SQLite, WAL mode.
-- All timestamps are epoch milliseconds UTC unless noted; event "day" and
-- "night" logic use Asia/Shanghai (UTC+8) at query time.

PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

-- One account owns one or more devices.  Tokens are account level and are
-- store only as SHA-256 hashes; each device join issues its own token.
CREATE TABLE IF NOT EXISTS accounts (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    account_code      TEXT    NOT NULL UNIQUE,       -- QL-XXXX
    tariff_version    TEXT    NOT NULL,
    nickname          TEXT    NOT NULL DEFAULT '',
    unit_seconds      INTEGER NULL,                  -- NULL = global UNIT_SECONDS
    exemption_per_day INTEGER NULL,                  -- NULL = global EXEMPTION_PER_DAY
    created_at        INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tokens (
    token_hash   TEXT    PRIMARY KEY,                -- sha256 hex of the bearer token
    account_id   INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    created_at   INTEGER NOT NULL,
    last_used_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_tokens_account ON tokens(account_id);

-- One active, single-use invitation per account. Store only its hash.
CREATE TABLE IF NOT EXISTS account_invites (
    account_id INTEGER PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    invite_hash TEXT NOT NULL UNIQUE,
    expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id   INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id    TEXT    NOT NULL,
    model        TEXT    NOT NULL DEFAULT '',
    app_version  TEXT    NOT NULL DEFAULT '',
    created_at   INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    UNIQUE(account_id, device_id)
);

-- Notification metadata.  (account_id, event_id) is UNIQUE => idempotent ingest.
-- Title is optional since v1.6.0 (the App only uploads titles when the user
-- opts in), so it defaults to ''.  dnd / notice are per-event booleans only --
-- never any notification text -- used by the v2026-11-c pricing.
CREATE TABLE IF NOT EXISTS events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id  INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id   TEXT    NOT NULL,
    event_id    TEXT    NOT NULL,
    pkg         TEXT    NOT NULL,
    app         TEXT    NOT NULL,
    title       TEXT    NOT NULL DEFAULT '',
    ts          INTEGER NOT NULL,                    -- event time, epoch ms
    priority    INTEGER NOT NULL DEFAULT 0,
    category    TEXT    NOT NULL DEFAULT 'system',
    ongoing     INTEGER NOT NULL DEFAULT 0,
    clearable   INTEGER NOT NULL DEFAULT 1,
    dnd         INTEGER NOT NULL DEFAULT 0,          -- delivered during DND (0/1)
    notice      INTEGER NOT NULL DEFAULT 0,          -- group broadcast (0/1)
    conversation_class TEXT NOT NULL DEFAULT '',    -- rate code only, no identity
    received_at INTEGER NOT NULL,
    UNIQUE(account_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_events_account_ts ON events(account_id, ts);
CREATE INDEX IF NOT EXISTS idx_events_account_pkg_ts ON events(account_id, pkg, ts);
CREATE INDEX IF NOT EXISTS idx_events_account_device_ts ON events(account_id, device_id, ts);

-- Stable internal invoice identifiers, never used as public credentials.
CREATE TABLE IF NOT EXISTS invoices (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_id TEXT    NOT NULL UNIQUE,              -- INV-YYYYMMDD-XXXX
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    date       TEXT    NOT NULL,                     -- YYYY-MM-DD (Asia/Shanghai)
    created_at INTEGER NOT NULL,
    UNIQUE(account_id, date)
);
CREATE INDEX IF NOT EXISTS idx_invoices_account_date ON invoices(account_id, date);

-- Created only when an authenticated user explicitly shares an invoice.
CREATE TABLE IF NOT EXISTS receipt_shares (
    invoice_id TEXT PRIMARY KEY REFERENCES invoices(invoice_id) ON DELETE CASCADE,
    share_id TEXT NOT NULL UNIQUE
);
