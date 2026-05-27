-- Multi-account broker credentials keyed by Discord user ID + label.
-- api_secret stored plaintext — bot owner is responsible for DB file security.

CREATE TABLE IF NOT EXISTS broker_accounts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_user_id TEXT NOT NULL,
    broker       TEXT NOT NULL DEFAULT 'KRAKEN',  -- KRAKEN | ALPACA (future)
    label        TEXT NOT NULL DEFAULT 'default',  -- user-chosen name, e.g. "main", "paper"
    api_key      TEXT NOT NULL,
    api_secret   TEXT NOT NULL,
    active       INTEGER NOT NULL DEFAULT 1,       -- 1 = this is the user's active account for this broker
    created_at   INTEGER NOT NULL DEFAULT (strftime('%s','now')),
    UNIQUE(discord_user_id, broker, label)
);

CREATE INDEX IF NOT EXISTS idx_broker_accounts_user ON broker_accounts(discord_user_id, broker);
