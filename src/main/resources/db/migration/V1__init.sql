-- M2: initial schema for trading-bot persistence
-- All timestamps stored as Unix epoch seconds (INTEGER) for SQLite compatibility.

CREATE TABLE IF NOT EXISTS watchlist (
    ticker      TEXT    NOT NULL PRIMARY KEY,
    auto_play   INTEGER NOT NULL DEFAULT 0,  -- 1 = auto, 0 = watch-only
    added_at    INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);

CREATE TABLE IF NOT EXISTS analyses (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ticker      TEXT    NOT NULL,
    run_at      INTEGER NOT NULL DEFAULT (strftime('%s','now')),
    result_text TEXT,
    ideas_json  TEXT    -- JSON array of TradeIdea objects
);

CREATE INDEX IF NOT EXISTS idx_analyses_ticker ON analyses(ticker);

CREATE TABLE IF NOT EXISTS setups (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    analysis_id INTEGER REFERENCES analyses(id),
    ticker      TEXT    NOT NULL,
    direction   TEXT    NOT NULL,  -- LONG | SHORT
    entry       REAL    NOT NULL,
    stop_loss   REAL    NOT NULL,
    target      REAL    NOT NULL,
    conviction  TEXT    NOT NULL,  -- HIGH | MEDIUM | LOW
    created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);

CREATE TABLE IF NOT EXISTS orders (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    setup_id    INTEGER REFERENCES setups(id),
    ticker      TEXT    NOT NULL,
    broker      TEXT    NOT NULL,  -- ALPACA | KRAKEN
    order_id    TEXT,              -- Alpaca UUID or Kraken entry txid
    tp_order_id TEXT,              -- Kraken TP txid (null for Alpaca bracket)
    direction   TEXT    NOT NULL,
    entry_price REAL    NOT NULL,
    stop_loss   REAL    NOT NULL,
    target      REAL    NOT NULL,
    qty         INTEGER NOT NULL,
    tif         TEXT,              -- day | opg | gtc
    status      TEXT    NOT NULL DEFAULT 'open',  -- open | filled | cancelled | partial
    placed_at   INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);

CREATE INDEX IF NOT EXISTS idx_orders_ticker  ON orders(ticker);
CREATE INDEX IF NOT EXISTS idx_orders_status  ON orders(status);

CREATE TABLE IF NOT EXISTS fills (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id     INTEGER REFERENCES orders(id),
    fill_price   REAL    NOT NULL,
    fill_qty     INTEGER NOT NULL,
    slippage_bps REAL,             -- (fill - intended) / intended * 10000
    filled_at    INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);

CREATE TABLE IF NOT EXISTS position_outcomes (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id     INTEGER REFERENCES orders(id),
    ticker       TEXT    NOT NULL,
    direction    TEXT    NOT NULL,
    entry_price  REAL    NOT NULL,
    exit_price   REAL    NOT NULL,
    qty          INTEGER NOT NULL,
    pnl          REAL    NOT NULL,
    outcome      TEXT    NOT NULL,  -- WIN | LOSS | BREAKEVEN
    closed_at    INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);

-- M3 view: per-setup hit-rate for feeding back into the LLM prompt
CREATE VIEW IF NOT EXISTS setup_performance AS
SELECT
    s.direction,
    s.conviction,
    COUNT(*)                                              AS total,
    SUM(CASE WHEN po.outcome = 'WIN' THEN 1 ELSE 0 END) AS wins,
    ROUND(100.0 * SUM(CASE WHEN po.outcome = 'WIN' THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_pct,
    ROUND(AVG(po.pnl), 2)                                AS avg_pnl
FROM setups s
JOIN orders o        ON o.setup_id    = s.id
JOIN position_outcomes po ON po.order_id = o.id
GROUP BY s.direction, s.conviction;

-- Session state: pending trade-idea menus keyed by Discord channel ID
CREATE TABLE IF NOT EXISTS session_setups (
    channel_id  TEXT    NOT NULL,
    ideas_json  TEXT    NOT NULL,
    saved_at    INTEGER NOT NULL DEFAULT (strftime('%s','now')),
    PRIMARY KEY (channel_id)
);
