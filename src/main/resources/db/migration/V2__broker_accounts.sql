-- Multi-account broker credentials keyed by Discord user ID + label.
--
-- SECURITY: api_key and api_secret are stored in PLAINTEXT.
-- Mitigations in place:
--   1. DatabaseManager.restrictDbFilePermissions() chmods the SQLite file to 0600
--      on every boot so only the owning OS user can read it.
--   2. The DB_PATH should live on a volume not shared with other users.
--   3. !account add commands are DM-only and the originating message is deleted.
--
-- TODO: encrypt api_secret (and api_key) at rest with AES-GCM keyed by an env-only
-- master secret (KRAKEN_VAULT_KEY) and migrate existing rows. Tracked as a follow-up
-- to the multi-account work; see the security review on commit history for context.

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
