# GitHub Issues — Paste-Ready

15 issues across 4 milestones. See [ROADMAP.md](./ROADMAP.md) for context.

## One-time setup before pasting

1. Go to https://github.com/daharimp/Trading-bot/labels and create these labels (any color):
   - `area:data`, `area:execution`, `area:persistence`, `area:scheduler`, `area:llm`, `area:ops`
   - `kind:bug`, `kind:feature`, `kind:refactor`, `kind:ops`
2. Go to https://github.com/daharimp/Trading-bot/milestones/new and create 4 milestones with these titles and descriptions:

   | Title | Description |
   |---|---|
   | `M0 — Quick wins` | Free wins, this week. Surface measurement data + stop the worst foot-guns before spending on SIP. |
   | `M1 — SIP + quote-aware execution` | $99/mo. Upgrade to Alpaca Algo Trader Plus, fetch live quotes, marketable-limit pricing. |
   | `M2 — Persistence` | SQLite on Fly Volume. Replace in-memory state with watchlist + setups + orders + fills tables. |
   | `M3 — Feedback loop` | Inject empirical setup performance into the LLM prompt and expose via `!stats`. |

3. For each issue below: click **New Issue** → paste the **Title** into the title box → paste the **Body** into the body box → in the right sidebar select the **Milestone** and apply the **Labels** listed under the issue.

---

# M0 — Quick wins

---

## Issue 1

**Title:** `[M0] Eliminate duplicate bar fetch in AnalysisService`

**Milestone:** `M0 — Quick wins`

**Labels:** `area:data`, `kind:refactor`

**Body:**

### Context
`AnalysisService.runFullAnalysis()` ([AnalysisService.java#L52-L99](src/main/java/com/tradingbot/analysis/AnalysisService.java#L52-L99)) fetches a `Map<Timeframe, BarSeries>` via `alpaca.getBars()` in a loop. `AnalysisService.fetchSeriesMap()` ([AnalysisService.java#L129-L141](src/main/java/com/tradingbot/analysis/AnalysisService.java#L129-L141)) runs the exact same loop a second time so [DiscordListener.java#L186](src/main/java/com/tradingbot/discord/DiscordListener.java#L186) can build the chart.

Every `!analyze` makes 5 redundant Alpaca bar requests (one per timeframe).

### Acceptance criteria
- [ ] `runFullAnalysis` returns its `seriesMap` (e.g. extend the result type, or expose it via a new field on the analysis result object).
- [ ] `DiscordListener` chart-building code consumes the existing `seriesMap` instead of calling `fetchSeriesMap`.
- [ ] `fetchSeriesMap` is removed.
- [ ] Manual test: `!analyze AAPL` succeeds and the chart still renders.
- [ ] Log line confirms only 5 (not 10) bar requests per `!analyze`.

### Notes
On the free 200 req/min IEX tier this is invisible. On M1's SIP feed this is real cost. Do it now while the refactor is small.

---

## Issue 2

**Title:** `[M0] Batch overnight watchlist bars into one multi-symbol request`

**Milestone:** `M0 — Quick wins`

**Labels:** `area:data`, `area:scheduler`, `kind:refactor`

**Body:**

### Context
`AlpacaClient.getStockBars()` ([AlpacaClient.java#L90-L113](src/main/java/com/tradingbot/alpaca/AlpacaClient.java#L90-L113)) accepts a single ticker and one timeframe. The overnight `WatchlistScheduler` loop calls it per-(ticker, timeframe), so a 10-symbol watchlist makes 50 requests serially.

Alpaca's `/v2/stocks/bars` endpoint accepts `symbols=AAPL,MSFT,...` (up to ~100). Batching the overnight pass collapses 50 requests to 5 (one per timeframe).

### Acceptance criteria
- [ ] Add `AlpacaClient.getStockBars(List<String> symbols, Timeframe tf)` returning `Map<String, BarSeries>`.
- [ ] Keep the existing single-symbol variant (used by `!analyze`).
- [ ] `WatchlistScheduler` overnight pass uses the batched variant.
- [ ] Manual test: run `!runanalysis` against a 3-symbol watchlist; verify all three get analyses and only 5 outbound bar requests fire.

### Notes
SIP feed has the same multi-symbol endpoint shape — this refactor pays off twice.

---

## Issue 3

**Title:** `[M0] Add intended-vs-fill slippage logging`

**Milestone:** `M0 — Quick wins`

**Labels:** `area:execution`, `kind:feature`

**Body:**

### Context
Today there is no way to answer "is Alpaca giving us good fills?" — `OrderManager` submits bracket orders ([AlpacaClient.java#L177-L213](src/main/java/com/tradingbot/alpaca/AlpacaClient.java#L177-L213)) and never compares intended price to fill price.

We need this data **before** deciding whether the M1 SIP upgrade (and any later broker migration) is worth it.

### Acceptance criteria
- [ ] On every order submission, record `{symbol, side, intended_price, submitted_at, alpaca_order_id}` in an in-memory `ConcurrentHashMap<String, IntendedOrder>` keyed by Alpaca order ID.
- [ ] Add a lightweight poller (every 30s) that queries `/v2/orders?status=closed&after=<bot_start>` and, for each filled order present in the map, logs one INFO line: `slippage: SYMBOL side=BUY intended=$X.XX filled=$Y.YY slippage_bps=N latency_ms=M`.
- [ ] Removes the map entry after logging.
- [ ] Manual test: place one `!play` order on paper, watch the log line appear within ~30s of fill.

### Notes
This is throwaway scaffolding — M2 replaces it with the `fills` table. Don't over-engineer. Goal is 50 fills of data before M1 starts.

---

## Issue 4

**Title:** `[M0] Replace stale overnight GTC limit with gap-check + opg TIF`

**Milestone:** `M0 — Quick wins`

**Labels:** `area:execution`, `area:scheduler`, `kind:bug`

**Body:**

### Context
`WatchlistScheduler.autoPlace()` ([WatchlistScheduler.java#L132-L157](src/main/java/com/tradingbot/scheduler/WatchlistScheduler.java#L132-L157)) calls `orderManager.placePlay(idea.getEntry(), ...)`. The entry price comes from analysis run at the scheduled time (default 23:00 ET) and gets submitted as a GTC limit. By 9:30am the next day the price is stale: if the stock gaps up the order never fills; if it gaps down the bot fills instantly at a price its model never validated.

Also: `AlpacaClient.placeOrder()` ([AlpacaClient.java#L184](src/main/java/com/tradingbot/alpaca/AlpacaClient.java#L184)) hardcodes `time_in_force=gtc` for all orders. The auto-play case should be `opg` (limit-on-open) so the entry happens in the opening auction.

### Acceptance criteria
- [ ] `AlpacaClient.placeOrder` accepts an optional `tif` parameter (default `gtc` to preserve existing call sites).
- [ ] `WatchlistScheduler.autoPlace`:
  - [ ] Before submitting, fetch a fresh quote via the latest-quote endpoint (paves the way for M1).
  - [ ] If `abs(midpoint - analysis_entry) / analysis_entry > 0.01`, skip the auto-play, post a Discord message with the gap percentage and the analysis for manual review, and increment a `skipped_gap_check` counter logged at INFO.
  - [ ] Otherwise submit with `tif=opg` instead of `gtc`.
- [ ] Unit test: 1.5% gap → skipped; 0.5% gap → submitted with `opg`.

### Notes
Until M1 ships the SIP feed, this quote will come from IEX and may be ~15min stale — still strictly better than using last night's close. Don't let perfect be the enemy of good.

---

# M1 — SIP + quote-aware execution

---

## Issue 5

**Title:** `[M1] Subscribe to Algo Trader Plus and flip data feed to SIP`

**Milestone:** `M1 — SIP + quote-aware execution`

**Labels:** `area:data`, `area:ops`, `kind:ops`

**Body:**

### Context
Free IEX feed covers ~3% of total US equity volume, so volume signals in the TA4j indicators are reading a small minority of prints. Algo Trader Plus ($99/mo) gives full SIP (100% NMS volume) on the same Alpaca SDK.

### Acceptance criteria
- [ ] Subscribe to Algo Trader Plus on https://app.alpaca.markets/.
- [ ] Add `ALPACA_DATA_FEED` env var (values: `iex` | `sip`, default `iex`).
- [ ] `AlpacaClient.getStockBars` reads the env var; if `sip`, drop the IEX fallback path ([AlpacaClient.java#L90-L113](src/main/java/com/tradingbot/alpaca/AlpacaClient.java#L90-L113)) and pass `feed=sip` to Alpaca.
- [ ] `fly secrets set ALPACA_DATA_FEED=sip --app trading-bot-satin-pond-7512`.
- [ ] `fly deploy`.
- [ ] Spot check: run `!analyze AAPL` and confirm the 5m bar volume is materially higher than before (SIP > IEX by ~30x for liquid names).
- [ ] Update `.env.example` and `README.md` API-usage table.

### Notes
**Sign the non-pro subscriber attestation** when prompted — only valid if account is in your personal name (not an LLC).

---

## Issue 6

**Title:** `[M1] Fetch live quote before every order placement`

**Milestone:** `M1 — SIP + quote-aware execution`

**Labels:** `area:execution`, `kind:feature`

**Body:**

### Context
No code path fetches `/v2/stocks/{symbol}/quotes/latest` today (grep-verified). Both `!pick` ([DiscordListener.java#L199-L241](src/main/java/com/tradingbot/discord/DiscordListener.java#L199-L241)) and `!play` ([DiscordListener.java#L243-L270](src/main/java/com/tradingbot/discord/DiscordListener.java#L243-L270)) submit limit orders pegged to stored or user-typed prices with no live quote check.

Once M1 #5 ships SIP, the latest-quote endpoint returns true NBBO and becomes the basis for marketable-limit pricing (next issue).

### Acceptance criteria
- [ ] Add `AlpacaClient.getLatestQuote(String symbol)` returning a `Quote(bid, ask, bidSize, askSize, timestamp)` record.
- [ ] Call it from `OrderManager.placePlay` immediately before constructing the bracket order; log `quote: SYMBOL bid=$X ask=$Y mid=$Z spread_bps=N`.
- [ ] If the quote is older than 5 seconds, log a WARN and proceed (don't block).
- [ ] Unit test with a mocked HTTP response.

### Notes
Pure plumbing. Pricing logic change is the next issue.

---

## Issue 7

**Title:** `[M1] Switch to marketable-limit pricing with slippage cap`

**Milestone:** `M1 — SIP + quote-aware execution`

**Labels:** `area:execution`, `kind:feature`

**Body:**

### Context
Today's order is a literal limit at the stored entry price, TIF GTC. The research doc recommends:

```
limit_price = min( ask + max(0.01, 0.0005 * ask),   // cross by 5 bps or 1 tick
                   intended_price * 1.003 )          // hard slippage cap 30 bps
```

This gives near-immediate fills (limit crosses the spread) with worst-case protection (cap prevents a runaway fill on a thin name).

### Acceptance criteria
- [ ] New helper `OrderPricer.marketableLimit(side, intendedPrice, quote)` returns the price computed by the formula above (mirror for sell side).
- [ ] `OrderManager.placePlay` uses the helper; uses `time_in_force=day` for intraday entries (not `gtc`).
- [ ] M0 #4's auto-play path continues to use `opg` for the opening auction (don't regress).
- [ ] Unit tests for: long crossing the ask by 1 tick on a $5 stock; long capped by the 30-bps slippage limit; short symmetric.
- [ ] Manual paper-trade test: place one `!play` on a liquid name, confirm fill within 1s and slippage_bps < 10 in the M0 #3 log.

### Notes
Don't use raw market orders. They can blow up pre-market against a stale NBBO.

---

# M2 — Persistence

---

## Issue 8

**Title:** `[M2] Provision Fly Volume and mount at /data`

**Milestone:** `M2 — Persistence`

**Labels:** `area:persistence`, `area:ops`, `kind:ops`

**Body:**

### Context
Fly machine filesystems are ephemeral. SQLite needs a persistent disk. The doc's verdict: a 1 GB volume ($0.15/mo) is the right pick for a single-instance hobby bot — managed Postgres would be 100× the cost for no HA benefit (canonical state lives at Alpaca anyway).

### Acceptance criteria
- [ ] `flyctl volumes create bot_data --size 1 --region iad --app trading-bot-satin-pond-7512`.
- [ ] Update `fly.toml` with the `[mounts]` block: `source = "bot_data"`, `destination = "/data"`.
- [ ] Verify default volume snapshot retention (5 days). Document in `README.md`.
- [ ] Deploy and SSH in (`flyctl ssh console`); confirm `/data` is writable and persists across `flyctl machine restart`.

### Notes
Fly's docs warn single-volume = no HA. Accepted: canonical state is at Alpaca; analyses are recoverable.

---

## Issue 9

**Title:** `[M2] Add SQLite + JDBI + Flyway + HikariCP and wire connection pool`

**Milestone:** `M2 — Persistence`

**Labels:** `area:persistence`, `kind:feature`

**Body:**

### Context
None of these libs are in `pom.xml` today (verified). The doc recommends JDBI 3 over Hibernate (small schema, raw-SQL-friendly, no entity-graph machinery for a 512 MB Fly machine).

### Acceptance criteria
- [ ] Add to `pom.xml`:
  - `org.xerial:sqlite-jdbc` (latest stable)
  - `org.jdbi:jdbi3-core` (3.45.x)
  - `org.flywaydb:flyway-core` (10.20.x)
  - `com.zaxxer:HikariCP` (6.x)
- [ ] Add `DATABASE_URL` env var (default `jdbc:sqlite:/data/bot.db`).
- [ ] At bootstrap in `Main.java`: build HikariDataSource with `maximumPoolSize=1` (SQLite write-serializes), run `Flyway.configure().dataSource(ds).load().migrate()`, build `Jdbi.create(ds).installPlugin(new SqlObjectPlugin())`.
- [ ] Bot starts and logs `Flyway: 0 migrations applied` on the empty DB.

### Notes
`maximumPoolSize=1` is intentional for SQLite to avoid `SQLITE_BUSY` write contention.

---

## Issue 10

**Title:** `[M2] Write V1__init.sql schema and DAOs`

**Milestone:** `M2 — Persistence`

**Labels:** `area:persistence`, `kind:feature`

**Body:**

### Context
Schema from the research doc covers everything M2 + M3 need: `watchlist`, `analyses`, `setups`, `orders`, `fills`, `position_outcomes`, and the `setup_performance` view.

### Acceptance criteria
- [ ] Create `src/main/resources/db/migration/V1__init.sql` with the schema below (copied verbatim from the doc).
- [ ] One DAO interface per table (e.g. `WatchlistDao`, `AnalysisDao`, `OrderDao`, `FillDao`, `SetupDao`, `PositionOutcomeDao`) using JDBI SQL Object API.
- [ ] One round-trip integration test per DAO (insert + select + assert).
- [ ] Boot the bot, confirm migration runs and creates all tables.

```sql
-- V1__init.sql
CREATE TABLE watchlist (
  symbol         TEXT PRIMARY KEY,
  channel_id     TEXT NOT NULL,
  added_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  notes          TEXT
);

CREATE TABLE analyses (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  symbol           TEXT NOT NULL,
  channel_id       TEXT NOT NULL,
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  source           TEXT NOT NULL,
  conviction       TEXT,
  setup_type       TEXT,
  entry_price      REAL,
  stop_price       REAL,
  target_price     REAL,
  claude_json      TEXT,
  fundamental_json TEXT,
  indicators_json  TEXT
);

CREATE INDEX idx_analyses_symbol_time ON analyses(symbol, created_at DESC);
CREATE INDEX idx_analyses_setup_time  ON analyses(setup_type, created_at DESC);

CREATE TABLE setups (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  analysis_id    INTEGER NOT NULL REFERENCES analyses(id),
  status         TEXT NOT NULL,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at      TIMESTAMP
);

CREATE TABLE orders (
  alpaca_order_id TEXT PRIMARY KEY,
  setup_id        INTEGER REFERENCES setups(id),
  symbol          TEXT NOT NULL,
  side            TEXT NOT NULL,
  qty             REAL NOT NULL,
  limit_price     REAL,
  stop_price      REAL,
  take_profit     REAL,
  order_class     TEXT,
  tif             TEXT,
  submitted_at    TIMESTAMP NOT NULL,
  status          TEXT NOT NULL
);

CREATE TABLE fills (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  alpaca_order_id TEXT NOT NULL REFERENCES orders(alpaca_order_id),
  filled_at       TIMESTAMP NOT NULL,
  filled_qty      REAL NOT NULL,
  filled_price    REAL NOT NULL,
  intended_price  REAL,
  slippage_bps    REAL,
  latency_ms      INTEGER
);

CREATE TABLE position_outcomes (
  setup_id        INTEGER PRIMARY KEY REFERENCES setups(id),
  realized_pnl    REAL,
  r_multiple      REAL,
  hold_minutes    INTEGER,
  exit_reason     TEXT
);

CREATE VIEW setup_performance AS
SELECT a.setup_type,
       COUNT(*)                                            AS n_trades,
       AVG(CASE WHEN o.r_multiple > 0 THEN 1.0 ELSE 0 END) AS win_rate,
       AVG(o.r_multiple)                                   AS avg_r,
       AVG(o.hold_minutes)                                 AS avg_hold_min
FROM position_outcomes o
JOIN setups   s ON s.id = o.setup_id
JOIN analyses a ON a.id = s.analysis_id
GROUP BY a.setup_type;
```

### Notes
SQLite types are flexible — `TIMESTAMP` and `REAL` work fine with JDBI's `java.time` and `BigDecimal` mappers.

---

## Issue 11

**Title:** `[M2] Migrate SessionStore (pending setups for !pick) to DB`

**Milestone:** `M2 — Persistence`

**Labels:** `area:persistence`, `area:llm`, `kind:refactor`

**Body:**

### Context
`SessionStore` ([SessionStore.java#L11-L22](src/main/java/com/tradingbot/discord/SessionStore.java#L11-L22)) is a `ConcurrentHashMap<String channelId, List<TradeIdea>>`. Restart loses every pending setup, breaking `!pick`.

After M2 #10 the `analyses` and `setups` tables exist; route storage through them.

### Acceptance criteria
- [ ] When `!analyze` finishes, insert one row into `analyses` per LLM-emitted setup (with `setup_type` parsed from the rationale or marked `unclassified`), plus one row into `setups` per setup with `status='pending'`.
- [ ] `!pick N` reads the last N analyses for the channel, presents the Nth.
- [ ] Drop the in-memory `SessionStore` class.
- [ ] Manual test: run `!analyze AAPL`, restart the bot, run `!pick 1` — works.

### Notes
Keep the existing in-message UX identical so users see no change.

---

## Issue 12

**Title:** `[M2] Migrate watchlist from in-memory to watchlist table`

**Milestone:** `M2 — Persistence`

**Labels:** `area:persistence`, `area:scheduler`, `kind:refactor`

**Body:**

### Context
`WatchlistScheduler` holds the watchlist in process memory (added via `!watch`). Restart wipes it — the bot misses the next overnight pass.

### Acceptance criteria
- [ ] `!watch <SYMBOL>` writes to `watchlist` table via `WatchlistDao`.
- [ ] `!unwatch <SYMBOL>` deletes.
- [ ] `!watchlist` reads from DB.
- [ ] Scheduler reads from DB on each overnight tick (not on boot — picks up adds made during the day).
- [ ] Manual test: `!watch TSLA`, redeploy, confirm `!watchlist` still shows TSLA.

### Notes
Trivial after M2 #10 ships the DAO.

---

## Issue 13

**Title:** `[M2] Wire Alpaca trade-updates stream into fills table`

**Milestone:** `M2 — Persistence`

**Labels:** `area:persistence`, `area:execution`, `kind:feature`

**Body:**

### Context
The M0 #3 in-memory slippage tracker dies on restart and only knows about orders the running process submitted. The Alpaca trade-updates WebSocket (`wss://api.alpaca.markets/stream`, distinct from market-data) pushes every fill event for the account.

This is the first WebSocket in the codebase — none exists today.

### Acceptance criteria
- [ ] Add `AlpacaTradeUpdatesClient` that connects to the trade-updates stream, authenticates, subscribes to `trade_updates`, and on each `fill`/`partial_fill` event inserts a row into `fills` (joining `intended_price` from `orders.limit_price` for the slippage_bps calculation).
- [ ] Run it on a dedicated thread (don't block Reactor's Discord4J pool).
- [ ] On startup: also call `/v2/orders?status=closed&after=<last_seen_fill_time>` and backfill any fills missed while offline.
- [ ] Auto-reconnect on disconnect with exponential backoff (cap 60s).
- [ ] Delete the M0 #3 in-memory tracker.

### Notes
Alpaca allows only 1 active stream connection per account. Don't run paper + live under the same key.

---

# M3 — Feedback loop

---

## Issue 14

**Title:** `[M3] Inject setup_performance stats into Claude technical-analysis prompt`

**Milestone:** `M3 — Feedback loop`

**Labels:** `area:llm`, `kind:feature`

**Body:**

### Context
Once `setup_performance` has 20+ trades per setup type, real hit-rate data should calibrate LLM conviction. From the doc:

> Historical hit-rate context (from this bot's last N trades): `ema_trend_continuation` 62% win rate, +0.8 R avg, 14 trades. `rsi_bounce` 38% win rate, -0.2 R avg, 9 trades.

### Acceptance criteria
- [ ] Before calling the LLM in `TechnicalAnalyst`, `SELECT * FROM setup_performance`.
- [ ] Format as a markdown block prepended to the system prompt (only include setup types with `n_trades >= 5` to avoid noise).
- [ ] Add one log line per call: `prompt_augmented: setups=N`.
- [ ] Manual test: hand-insert ~10 rows into `position_outcomes`, confirm the prompt visibly includes the stats block, confirm Claude references it in its rationale.

### Notes
Don't over-engineer the setup-type classification — start with the rules-engine label (`ema_trend`, `rsi_bounce`, `volume_cross`) set in M2 #11. Refine later.

---

## Issue 15

**Title:** `[M3] Add !stats Discord command`

**Milestone:** `M3 — Feedback loop`

**Labels:** `area:llm`, `kind:feature`

**Body:**

### Context
Closes the feedback loop visibly: the same `setup_performance` data the LLM sees, formatted for the human.

### Acceptance criteria
- [ ] New command `!stats` in `DiscordListener.route()`.
- [ ] Queries `setup_performance` and formats as a Discord-friendly table (use existing 2000-char chunking if needed).
- [ ] Optional: `!stats <setup_type>` drills into per-trade detail (last 10 trades, with R-multiple).
- [ ] Manual test: shows correct numbers after some position outcomes have been recorded.

### Notes
This is the "did the bot actually make money" check. Put a link to `!stats` in `!help`.
