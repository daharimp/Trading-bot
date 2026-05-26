# Discord Trading Bot — Upgrade Plan for Real-Time Data, Better Fills, and Persistent Memory (May 2026)

## TL;DR
- **Buy Alpaca Algo Trader Plus at $99/mo** as the single highest-leverage change: it converts your IEX-only feed (which IEX itself reports as ~3.2% of total U.S. equity volume, ~6% of on-exchange volume) into full SIP (100% of NMS-listed volume) with unlimited WebSocket symbols, gives you real-time tick/quote/bar streaming directly in the `alpaca-java` SDK you already use, and unlocks better limit pricing without changing brokers. Defer Polygon Advanced ($199) and Databento Standard ($199) until you outgrow Alpaca or specifically need MBO/L3 microstructure.
- **Stop sending GTC limit orders pegged to a stale analysis price.** Switch to quote-aware *marketable limits* (cross the NBBO by ≤ 1–2 ticks with a slippage cap), use `time_in_force=day` (or `opg` for the scheduled open) instead of GTC, re-quote and gap-check before the auto-play fires, and log fill-price-vs-intended on every order. Migrate to IBKR Pro for execution only if your slippage data shows >15 bps of consistent drag — the retail fill-quality picture is more nuanced than vendor marketing suggests.
- **Add persistence on a 1 GB Fly Volume with SQLite + Flyway migrations, accessed via JDBI 3.** Persist watchlist, setups, orders, fills, and per-setup-type win-rate stats, then feed historical hit-rate context into the Claude technical-analysis prompt. SQLite-on-volume is the right pick for a single-instance hobby bot on Fly.io's shared-cpu-1x; jump to managed Postgres only if you ever need HA.

---

## Key Findings

1. **Alpaca Algo Trader Plus is $99/month and is the cheapest path to full SIP real-time tick data for a Java/Alpaca-native stack** — confirmed from Alpaca's data page: "Algo Trader Plus … $99/mo … Real-time … All US Exchanges … Unlimited API calls … Unlimited symbols" on WebSocket, vs. the free tier's "IEX … 200 API calls/min … Limited to 30 symbols." Per IEX's own newsroom and an IEX CEO statement in Traders Magazine (Jan 2026), IEX runs ~3.2% of total U.S. equity volume (including off-exchange) and ~6% of on-exchange share. Either way, any analysis dependent on volume/liquidity is structurally noisy on the free feed.
2. **Polygon.io ("Massive") equities tiers are $0 / $29 / $79 / $199 per month, and only the $199 Advanced tier delivers real-time tick + NBBO data.** Starter ($29) and Developer ($79) are explicitly 15-minute delayed on the per-endpoint plan tables. For a single-user retail bot, Polygon Advanced is ~2× the cost of Alpaca Algo Trader Plus for similar tick coverage, *and* you would still need Alpaca for execution.
3. **Databento has no retail flat-rate plan below $199/mo for live US-equities data.** Their "Standard" plan is $199/mo (verbatim "Ideal for individuals and small teams") and is the floor for live MBP-1 / EQUS.MINI streaming; "Plus" jumps to $1,399/mo with an annual contract. Pay-as-you-go is historical-only for US equities since their January 13, 2025 pricing change. Databento only makes sense if you specifically want MBO/L3 microstructure.
4. **IEX Cloud is dead** — fully retired August 31, 2024. Two former execs spun up "Blue Sky Data" mirroring the old API, but it's too new (launched Sept 2024) for production. Ignore any IEX Cloud guidance you encounter.
5. **Interactive Brokers gives you real-time consolidated US data via the US Securities Snapshot and Futures Value Bundle at $10/month, waived if monthly commissions reach $30 USD** (verbatim from IBKR's market data pricing page). Plus the streaming add-on is $4.50/mo (not commission-waivable). IBKR has no first-party Java SDK on par with `alpaca-java`, but the modern Web API (REST + WebSocket) is fully Java-callable; the legacy TWS API requires a local TWS/Gateway process incompatible with your shared-cpu-1x Fly footprint.
6. **Tradier gives you free real-time consolidated data with a funded brokerage account** ("Real-time data is available to all Tradier Brokerage account holders for US-based stocks and options … pulled from a consolidated feed from all exchanges"). It is a credible Alpaca-class alternative if you ever want to drop Alpaca's $99/mo data fee.
7. **Alpaca's bracket order is a single REST call (`order_class=bracket`)** that Alpaca treats atomically (the take-profit limit + stop-loss are queued as conditional children). IBKR requires you to build OCA groups manually. Preserving this abstraction is a real reason to stay on Alpaca.
8. **Alpaca runs payment-for-order-flow** — but the actual cost is small. The NBER working paper "What Does Best Execution Look Like? Payment for Order Flow" (Ernst & Spatt, NBER WP 29883, also cited by the SEC's DERA) estimates "the typical payment for routing a 100 share equity trade is around 20 cents" — i.e., ~$0.20 per 100 shares to the routing broker, not the $1–3 figure cited in some popular write-ups. For your hobby AUM this is essentially noise vs. timing risk and gap risk.
9. **Retail fill-quality rankings are messier than vendor marketing suggests.** The Journal of Finance paper by Schwarz et al. (2025, doi:10.1111/jofi.13467), which ran 85,000 simultaneous market orders across six retail brokers, reported "IBKR Pro has the lowest level of PI [price improvement at the midpoint or better] with only 16% of trades occurring at the midpoint price or better," while TD Ameritrade led at 69%. IBKR publishes "netted" stats (improvement minus dis-improvement) that show better, but that's a different methodology. Conclusion: don't blindly assume IBKR Pro will give you better fills than Alpaca for typical retail-sized limit orders; measure first.
10. **Your current code has a redundant bars fetch per `!analyze`** (once in `runFullAnalysis`, again in `fetchSeriesMap` for charting). On the free 200 req/min IEX tier this is invisible; on Algo Trader Plus you want that headroom for live ticks. Cache the `BarSeries` map once and pass it into the charter.
11. **Fly.io's filesystem is ephemeral; SQLite on a Fly Volume is the documented retail path.** Fly's docs warn: "Running an app with a single Machine and volume leaves you at risk for downtime and data loss" — acceptable for a hobby bot because canonical state (positions, open orders) is replicated at Alpaca, and Fly's default 5-day volume snapshots cover the rest.
12. **Flyway Community + plain SQL migrations is the lightest-weight Java migration tool**; Liquibase is overkill for a single-engine bot. Pair with **JDBI 3** (not Hibernate) — small schema, raw SQL, no entity-graph machinery to carry.
13. **Discord4J and a market-data WebSocket can co-exist in one JVM** on Java 21 virtual threads, but Alpaca's stream allows only **1 active connection per account** — don't run a paper-and-live bot in parallel under the same key.

---

## Details

### Area 1 — Real-Time Live Tick Data (cheaper + higher quality)

#### The SIP vs. IEX issue, plainly
Your bot today subscribes to the free Alpaca feed, which pulls *only* from the IEX exchange. Three concrete consequences:

- **Volume signals lie.** Your TA4j volume-cross indicator is reading a small minority of actual prints. Cross-venue accumulation/distribution is invisible.
- **Quotes are looser than NBBO.** IEX BBO is wider than true NBBO most of the time, so any future quote-aware limit pricing will be miscalibrated.
- **Extended-hours data is sparse.** Pre-market gap analysis on IEX-only is unreliable; many overnight-watchlist names won't have meaningful IEX prints between 4am–9:30am ET.

SIP (Securities Information Processor) is the regulated consolidated tape covering 100% of NMS-listed volume — Alpaca pulls from both CTA (NYSE-administered) and UTP (Nasdaq-administered) tapes and combines them.

#### Provider comparison for a single retail user (May 2026)

| Provider / Tier | Monthly | Real-time? | Tick/Quote/WS | Historical | Java access | Notes |
|---|---|---|---|---|---|---|
| Alpaca Basic (current) | $0 | IEX only | WS, **30 symbols cap** | 7+ yrs | `alpaca-java` 10.0.1 (uses OkHttp, same dep you already have) | 200 API/min |
| **Alpaca Algo Trader Plus** | **$99** | **Full SIP** | WS, **unlimited symbols**, real-time options OPRA included | 7+ yrs | Same SDK, just switch `data_api_type=sip` | Best Java DX, no broker change |
| Polygon Stocks Basic | $0 | End-of-day | none useful | 2 yrs | REST/WS, no official Java SDK | 5 calls/min |
| Polygon Stocks Starter | $29 | 15-min delayed | delayed WS | 5 yrs | REST/WS | Not real-time |
| Polygon Stocks Developer | $79 | 15-min delayed | delayed WS | 10 yrs | REST/WS | Still delayed |
| **Polygon Stocks Advanced** | **$199** | **Real-time** | tick trades+NBBO via WS, unlimited API calls | 15+ yrs | REST/WS only (community Java wrappers) | "Individual use — Non-pros only" |
| Databento Usage-based | pay-go | historical only (live deprecated for equities Jan 2025) | tick historical | varies | Official Python/Rust/C++ only; HTTP/Raw API from Java | $125 signup credit |
| **Databento Standard** | **$199** | Live MBP-1 + EQUS.MINI | tick+NBBO WS | 1y L1, 1mo L2/L3 | Same | Only sub-$1,399 flat-rate with live |
| Finnhub paid | from ~$50/mo | Real-time (US) | WS | varies | REST/WS, no Java SDK | News feed has reported lag issues |
| Twelve Data Pro | $99 | Real-time | WS (credit-based) | varies | REST/WS | Complicated credit accounting |
| Financial Modeling Prep | from ~$19/mo flat | Real-time | WS | varies | REST/WS | Cheapest live-WS-without-broker option |
| Tradier Brokerage | $0 (with funded account) | Real-time consolidated | HTTP streaming + WS | full | REST/WS, no Java SDK | Brokerage account required |
| **IBKR (Web API)** | **$10/mo** (waived if commissions ≥ $30/mo); +$4.50/mo streaming add-on | Real-time NBBO | WS, but rate-limited per "market data lines" | full | Web API REST/WS; community TWS Java SDKs | $500 funded minimum |
| Schwab/thinkorswim API | $0 with funded account | Real-time | streaming | full | REST/WS, no Java SDK | Complex OAuth, hobby-unfriendly |

#### Recommendation for Area 1
**Subscribe to Alpaca Algo Trader Plus ($99/mo).** It is the single change that moves your data quality from "minority-volume IEX, delayed bars" to "100% SIP volume, live ticks" without touching the broker abstraction, the SDK, or your bracket-order code. Switch your config from `data_api_type=iex` to `data_api_type=sip` and update the WebSocket URL from `/v2/iex` to `/v2/sip`.

**Do not** go to Polygon Advanced ($199) unless you specifically need 15+ years of tick history for backtesting; for a forward-looking analysis bot, Alpaca's 7+ years is plenty. **Do not** go to Databento Standard ($199) unless you specifically want MBO/L3 order-book microstructure (you don't, given the TA4j indicators you're computing).

**Exchange / non-pro agreement implication:** Alpaca, Polygon Advanced, and IBKR all gate real-time SIP data behind a *non-professional subscriber* attestation. You qualify as long as you are an individual, not registered with the SEC/FINRA, not employed by a financial firm in a securities capacity, and not trading through an entity (LLC, corp, trust). Polygon Advanced's pricing card states "Individual use — Non-pros only" explicitly. Make sure the bot's Alpaca account is in your personal name.

---

### Area 2 — Trade Execution & Fill Optimization

#### Diagnosis of current behavior
Your scheduler runs overnight watchlist analysis and places **GTC bracket limit orders at the analysis-time price** via Alpaca's `/v2/orders` with `order_class=bracket`. Three structural problems:

1. **Stale limit price.** A limit at the 8pm analysis close is essentially never the right price at 9:30am next morning. If the stock gaps up, your buy limit never fills (you miss the trade you wanted). If it gaps down, you fill instantly at a price your model didn't validate.
2. **GTC entry on a "moment-in-time" thesis.** A high-conviction setup from last night becomes stale by mid-day. A GTC buy limit sitting unfilled all day exposes you to news risk you didn't underwrite.
3. **No quote awareness.** You're pricing based on the last 5-minute close bar, not the live bid/ask. On a name with a $0.05 spread that's fine. On a thin small-cap with a $0.30 spread it's a meaningful miss.

#### Concrete order-handling changes

**Change 1 — Use marketable limits with a slippage cap.** Once you have SIP via Algo Trader Plus, fetch the latest quote (`/v2/stocks/{symbol}/quotes/latest`) immediately before sending the bracket. For a long:
```
limit_price = min( ask + max(0.01, 0.0005 * ask),   // cross by 5 bps or 1 tick
                   intended_price * 1.003 )          // hard slippage cap
```
This combines the immediacy of a market order with the worst-case protection of a limit. SteadyOptions' rule of thumb applies: place a buy limit one tick above the ask so the worst case is one-tick slippage. Never use raw market orders against Alpaca, especially pre-market.

**Change 2 — `time_in_force=day` for `!play` and intraday entries.** Only the scheduled overnight setup needs a queued open. For that case, use `time_in_force=opg` (Market-On-Open / Limit-On-Open) with a price floor, so the entry actually happens in the opening auction rather than sitting as a stale GTC at the prior day's close.

**Change 3 — Reprice on gap.** Before the auto-play scheduler fires, re-fetch the symbol's last quote. If `(quote.midpoint - analysis_price) / analysis_price > gap_threshold` (start with 1.0%), cancel the auto-play and post the analysis to Discord for manual review instead of firing the order. This is the single biggest win for the overnight workflow.

**Change 4 — Persist intended-vs-fill slippage.** When an order fills, write `{intended_price, fill_price, slippage_bps, latency_ms}` into the new `fills` table (see Area 4). After 50 fills you'll know empirically whether you're getting price improvement or paying through the bid.

#### Broker fill quality — is Alpaca holding you back?

Alpaca discloses payment-for-order-flow ("Alpaca Securities LLC receives payment for order flow by directing customer orders to specific market centers for execution"). Per NBER WP 29883 (Ernst & Spatt) the typical PFOF payment is ~$0.20 per 100-share equity trade to the broker — that is the *upper bound* of what you lose in execution quality relative to a "pure" NBBO route, and most retail orders still receive *some* price improvement from the wholesaler.

The IBKR Pro story is more complicated than the marketing implies:

- **IBKR's own SmartRouting documentation** states the router "continuously evaluates fast changing market conditions and dynamically re-routes all or parts of your order seeking to achieve optimal execution," and includes 8 dark pools and the IBKR ATS in its logic.
- **But the Journal of Finance paper by Schwarz et al. (2025)**, which ran 85,000 simultaneous market orders across six retail brokers, found "IBKR Pro has the lowest level of PI [midpoint-or-better fills] with only 16% of trades occurring at the midpoint price or better," with TD Ameritrade leading at 69%. IBKR's published statistics use a different "netted" methodology (improved minus dis-improved cents), which can look favorable while the midpoint-PI percentage is lower.

**Practical recommendation:** Stay on Alpaca for now. Your bot's edge (if any) lives in the technical+fundamental thesis, not in the last cent of execution. Don't migrate to IBKR on faith — measure your Alpaca slippage first (Change 4 above). Revisit IBKR Pro only if logged slippage shows > 10–15 bps of consistent execution drag.

**Java-accessibility note for IBKR:** No official Java SDK equivalent to `alpaca-java`. Options are (a) the Web API (REST + WebSocket, JSON, the modern path; needs OAuth 2.0 and the Client Portal Gateway for retail users), or (b) the legacy TWS API which requires a running TWS / IB Gateway local process — incompatible with your shared-cpu-1x Fly.io footprint.

**Tradier** is a credible Alpaca-class alternative if you ever drop Alpaca: real-time consolidated data is free with a brokerage account, REST + WebSocket streaming, but no first-party Java SDK and bracket-order semantics are less ergonomic than Alpaca's `order_class=bracket`.

---

### Area 3 — Market Data Cost Optimization

#### Total monthly cost for a hobby bot analyzing ~10–50 tickers a few times/day

| Stack | Monthly | Notes |
|---|---|---|
| **Today (Alpaca free IEX + Alpha Vantage free)** | **$0** | IEX-only coverage; 25 fundamental req/day |
| **Recommended: Alpaca Algo Trader Plus + Alpha Vantage free** | **$99** | Full SIP real-time, keep fundamental free tier |
| Alpaca Algo Trader Plus + FMP Starter | ~$118 | If Alpha Vantage 25/day is too tight, FMP starter (~$19/mo flat) gives better fundamentals |
| Polygon Advanced + Alpaca free (data on Polygon, execution on Alpaca) | $199 | Only worth it if Polygon's tick depth materially better for your strategy |
| Databento Standard + Alpaca free | $199 | MBO/L3 — overkill for TA4j-style analysis |
| IBKR Pro broker-bundled, data fee waived | ~$0 net (if you trade $30+/mo commissions) | Best documented routing intent; worst Java DX; midpoint-PI debatable |

#### Caching/efficiency fixes you can do today (zero cost)
- **Eliminate the duplicate bar fetch.** In your current `runFullAnalysis` → `fetchSeriesMap` pipeline, the second fetch is purely for the XChart renderer. Pass the already-built `Map<TimeFrame, BarSeries>` directly into the chart helper. On the free tier this saves ~50% of your bar requests and ~30% of your latency. On Algo Trader Plus it's free headroom for live ticks.
- **Cache the latest quote within a single analysis session.** Fetch once per ticker per analysis; reuse across timeframes.
- **Batch the watchlist analysis into a single multi-symbol `/v2/stocks/bars` request.** Alpaca's bars endpoint accepts up to ~100 symbols per call; batching cuts overnight latency by an order of magnitude.
- **Move fundamental data behind a `last_fetched_at` check in the new `fundamentals` table** — OVERVIEW data doesn't change intraday, so don't burn Alpha Vantage's 25 req/day budget refetching the same ticker.

---

### Area 4 — Persistence Architecture

#### What to persist (priorities, top to bottom)

1. **Watchlist** — survives restarts, currently lost.
2. **Pending TradeIdeas / setups per Discord channel** — currently in `SessionStore` ConcurrentHashMap.
3. **Placed orders** (intent, parameters, Alpaca order ID).
4. **Fills** (Alpaca fill events, price, slippage vs. intended).
5. **Analyses** (the JSON of each Claude/OpenRouter response, ticker, timestamp, setup-type classification).
6. **Realized P&L per closed position**, joined back to the setup that originated it.
7. **Per-setup-type rolling stats** (win rate, avg R-multiple, avg holding time) — derived view, can be materialized.
8. **Fundamental snapshots** (cache to stop refetching the same Alpha Vantage data).

#### Storage choice — recommendation: **SQLite on a Fly Volume**

The realistic options:

| Option | Cost | Fly.io fit | Pros | Cons |
|---|---|---|---|---|
| **SQLite on Fly Volume** | Volume only ($0.15/GB/mo, so ~$0.15/mo for 1 GB) | Native | Zero extra ops, embedded, ACID, perfect for single-instance | Single-machine; volume snapshots only |
| H2 embedded | $0 | Same as SQLite | Pure-Java, no native deps | Less battle-tested, weaker durability story |
| DuckDB | $0 | Same | OLAP-fast for analytics queries | Overkill for row-level OLTP your bot does |
| Fly Managed Postgres | ~$10–15/mo minimum | Native | HA, snapshots, scales out | Extra app + cost; you don't need HA |
| Neon / Supabase (external) | $0 free tier | External | Generous free tier, web dashboard | Network latency, secrets sprawl, single point of failure outside Fly |

For a single-instance hobby bot on shared-cpu-1x where downtime during deploys is already acceptable, **SQLite on a Fly Volume is the right pick.** Cost is essentially zero, the database file lives next to the JVM, backups are automatic via Fly's daily volume snapshots (default 5-day retention), and you remove a whole category of network-connection failure modes.

**The Fly.io single-volume warning.** Fly's docs are blunt: "Running an app with a single Machine and volume leaves you at risk for downtime and data loss." For a trading bot this is acceptable because (a) all canonical state (positions, open orders) is also held by Alpaca, (b) loss of a few hours of analyses is recoverable, and (c) you can re-fetch fills from Alpaca's `/v2/orders` history endpoint. If you ever want belt-and-suspenders, add a nightly `litestream` replication to S3/R2 — but skip it for v1.

#### Schema sketch (SQLite, with comments)

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
  source           TEXT NOT NULL,        -- 'manual' | 'overnight'
  conviction       TEXT,                 -- LOW|MED|HIGH
  setup_type       TEXT,                 -- 'ema_trend' | 'rsi_bounce' | 'volume_cross' | ...
  entry_price      REAL,
  stop_price       REAL,
  target_price     REAL,
  claude_json      TEXT,                 -- raw LLM response
  fundamental_json TEXT,
  indicators_json  TEXT
);

CREATE INDEX idx_analyses_symbol_time ON analyses(symbol, created_at DESC);
CREATE INDEX idx_analyses_setup_time  ON analyses(setup_type, created_at DESC);

CREATE TABLE setups (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  analysis_id    INTEGER NOT NULL REFERENCES analyses(id),
  status         TEXT NOT NULL,        -- 'pending' | 'placed' | 'filled' | 'closed' | 'cancelled' | 'expired'
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
  order_class     TEXT,                -- 'bracket' | 'simple'
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
  slippage_bps    REAL,                -- (filled - intended) / intended * 10000, signed
  latency_ms      INTEGER
);

CREATE TABLE position_outcomes (
  setup_id        INTEGER PRIMARY KEY REFERENCES setups(id),
  realized_pnl    REAL,
  r_multiple      REAL,                -- realized / risk-at-entry
  hold_minutes    INTEGER,
  exit_reason     TEXT                  -- 'take_profit' | 'stop_loss' | 'manual' | 'expired'
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

#### Java persistence library — recommendation: **JDBI 3 + Flyway Community**

For this codebase (small, single-developer, raw-SQL friendly, no JPA legacy):

- **JDBI 3** for data access. SQL stays explicit and reviewable, integrates cleanly with `java.time` and your existing `Gson` for the JSON columns, no annotation-driven magic. Roughly 200 lines of DAO code will cover this whole schema.
- **Flyway Community** (still free in 2026 for any database including SQLite) for migrations. SQL-only, naming convention `V1__init.sql`, `V2__add_fundamental_cache.sql`, etc. Flyway runs them on JVM startup.
- **HikariCP** for the connection pool (one connection is plenty for SQLite — set `maximumPoolSize=1` to avoid SQLite write contention).
- **`org.xerial:sqlite-jdbc`** as the driver.

**Why not Hibernate/JPA?** Your domain is small, the queries you need are SQL-shaped (joins for the performance view, time-bucketed aggregates), and Hibernate's entity-graph machinery buys you nothing here. It adds startup time and ~5 MB to your shaded jar for a 512 MB Fly machine.

**Why not Liquibase?** Overkill for one database engine and a single developer. Flyway's "SQL file with version prefix" model is exactly what you want. Liquibase 5.0 (Sept 2025) also moved to Java 17+ and the Functional Source License — Flyway Community is simpler.

#### Feedback loop — how persisted outcomes improve future analyses

Once `setup_performance` has 20–30 trades per setup type, inject a short context block into the Claude technical-analysis prompt:

> *Historical hit-rate context (from this bot's last N trades): `ema_trend_continuation` 62% win rate, +0.8 R avg, 14 trades. `rsi_bounce` 38% win rate, -0.2 R avg, 9 trades. `volume_cross_breakout` 71% win rate, +1.4 R avg, 7 trades.*

Two effects:
1. Claude calibrates conviction — a setup type with empirically poor hit rate gets pushed toward MED/LOW even when textbook-perfect.
2. You get an honest equity-curve view in a new `!stats` Discord command, which is what you actually want from a trading bot long-term.

This is *not* a learning loop in the gradient-descent sense — it's prompt augmentation with real performance data. Cost: one extra SELECT per analysis.

---

### Cross-Cutting Improvements (relevant only to the four areas above)

- **Concurrent WebSockets.** Once you add the Alpaca SIP stream you'll be holding two long-lived WebSockets in the same JVM: Discord4J's gateway and Alpaca's market-data stream. Discord4J runs on a Reactor scheduler; keep the Alpaca stream on its own thread. Never block either thread on database or LLM calls — hand off heavy work to a `Executors.newVirtualThreadPerTaskExecutor()` (you're on Java 21, virtual threads are free).
- **Reconnection.** `alpaca-java` 10.x has reconnect baked into `AlpacaWebsocket` (constants `MAX_RECONNECT_ATTEMPTS`, `RECONNECT_SLEEP_INTERVAL`). The dangerous default is silently dropping your subscription set on reconnect; wrap subscription state in a `volatile Set<String> activeSymbols` and replay it on every reconnect callback. Same pattern for Discord4J — its `GatewayClient` auto-reconnects but you must re-register listeners.
- **Single-connection limit.** Alpaca documents that "most users can have only 1 active stream connection" — including on Algo Trader Plus. Don't run paper + live bots under the same key.
- **Backpressure on the SIP firehose.** Subscribing to `*` (all symbols) on a 100% SIP feed will saturate a 512 MB Fly machine. Subscribe only to your watchlist + active positions. Use the Alpaca `setTradeSubscriptions(Set<String>)` / `setQuoteSubscriptions(Set<String>)` *replace* APIs (not the additive `subscribe`) to keep the set tight.
- **Persistence + restart resilience.** On boot: (1) Flyway migrate, (2) load watchlist from DB, (3) sync open orders from Alpaca via `/v2/orders?status=open` and reconcile with local `orders` table, (4) only then attach the Discord gateway. This ordering means a deploy mid-day can't drop a pending bracket.

---

## Recommendations (Staged)

### Stage 0 — Free wins, do this week
1. **Fix the duplicate bar fetch** in `runFullAnalysis`/`fetchSeriesMap`. ~30 min refactor.
2. **Stop the GTC-at-stale-price overnight pattern.** Switch the auto-play to: (a) re-quote at scheduled fire time, (b) cancel auto-play if gap > 1%, (c) use `time_in_force=opg` for the entry. ~2 hours.
3. **Add slippage logging** to the in-memory order tracker before persistence ships, so you can quantify execution drag from day one.
4. **Batch Alpaca bar requests** for the overnight watchlist into one `/v2/stocks/bars?symbols=...` call.

### Stage 1 — Spend $99/mo, do this month
5. **Subscribe to Alpaca Algo Trader Plus.** Flip `data_api_type` to `sip`. Switch your WebSocket URL from `/v2/iex` to `/v2/sip`. Spot-check volume/quote sanity on a few names.
6. **Add a real-time quote enrichment step before every order placement.** This is the marketable-limit change in Area 2.
7. **Subscribe to live trades for currently-held positions only** (not the whole watchlist) on the Alpaca stream — gives you the basis for trailing stops and partial-fill detection later, at zero incremental cost.

### Stage 2 — Persistence, next 2–4 weeks
8. **Provision a 1 GB Fly Volume:** `fly volumes create bot_data --size 1`. Cost ~$0.15/mo.
9. **Add the `db` Maven module:** `org.xerial:sqlite-jdbc`, `org.jdbi:jdbi3-core`, `org.flywaydb:flyway-core`, `com.zaxxer:HikariCP`. Set `DATABASE_URL=jdbc:sqlite:/data/bot.db` as a Fly secret.
10. **Write `V1__init.sql`** (schema above) and a `DAO` class per table.
11. **Backfill the watchlist and pending sessions** to the DB. Delete the in-memory `SessionStore` ConcurrentHashMap and replace with DB-backed reads.
12. **Wire fill events from Alpaca's trade-updates stream** (`wss://api.alpaca.markets/stream` — distinct from the market-data stream) into the `fills` table.
13. **Add a `!stats` Discord command** that runs `SELECT * FROM setup_performance` and posts a formatted table.

### Stage 3 — Closing the feedback loop
14. **Once you have 20+ closed trades per setup type**, augment the Claude technical-analysis prompt with the `setup_performance` rolling stats.
15. **Add a `!journal <setup_id>` command** for manual notes on what went right/wrong (markdown text column on `position_outcomes`).

### Thresholds that would change these recommendations
- **If overnight slippage logs show > 15 bps consistent drag** → open an IBKR Pro account and migrate execution (but verify the midpoint-PI question with a 100-order paper test first; the JoF 2025 evidence is not as flattering to IBKR as their marketing).
- **If your watchlist grows past ~500 active symbols on the SIP stream** → revisit Polygon Advanced ($199) for the unlimited-symbol stream guarantees.
- **If you start trading options or want MBO/L3 microstructure** → Databento Standard ($199) becomes interesting; nothing else gives you full order-book history at retail price.
- **If the bot ever stores customer/PII data, or you want HA** → migrate to Fly Managed Postgres (~$10–15/mo) and drop SQLite.
- **If Alpha Vantage's 25 req/day starts hurting on overnight watchlist analysis** → move to FMP at ~$19/mo flat for richer fundamentals without abandoning your OpenRouter LLM step.

---

## Caveats

- **Pricing is volatile.** The $99/mo Alpaca Algo Trader Plus and Databento's $199/mo Standard tier were verified on vendor pricing pages in May 2026; Polygon/Massive's full Stocks pricing card is JS-rendered and parts of its feature list were cross-verified through the analogous Options pricing card on the same site. Re-check before subscribing.
- **The "free" IBKR data path requires $30/mo in commissions** to waive the $10/mo bundle, plus another $4.50/mo for the streaming add-on which is *not* commission-waivable. For a low-volume hobby bot, expect ~$15/mo, not zero.
- **PFOF cost is small, not zero.** Per Ernst & Spatt's NBER work, ~$0.20 per 100-share equity trade is paid by the wholesaler to the broker — meaningful as a methodological footnote, negligible at your AUM. Don't switch brokers over PFOF alone.
- **Retail fill-quality marketing is not the same as third-party measurement.** The Journal of Finance Schwarz et al. (2025) study found IBKR Pro at 16% midpoint-or-better fills vs. TD Ameritrade at 69% on identical simultaneous market orders. IBKR's own "netted" PI statistics tell a different story. The right answer is to log your own slippage and decide empirically.
- **Fly Volumes are single-host, single-region.** Hardware loss = data loss between snapshots. Acceptable for a hobby trading bot where canonical position state lives at Alpaca, but not acceptable for any system of record.
- **Alpaca's bracket order is "Not Held" / "best efforts" once triggered** — per their disclosure: "Conditional orders are 'Not Held' orders whose execution instructions are on a best efforts basis upon being triggered." Don't assume bracket exits fill at the limit price you wrote.
- **Non-pro subscriber attestation.** Algo Trader Plus, Polygon Advanced, and IBKR all gate SIP behind a non-professional attestation. If your bot's account is in an LLC or trades any third-party funds, you owe the professional rate (and Polygon Advanced's "Individual use — Non-pros only" terms exclude you from that tier entirely).
- **`alpaca-java` is community-maintained** (Jacob Peterson, MIT license). It's the best Java option for Alpaca but isn't an official SDK. Pin to a known-good version (10.0.1 at time of writing) and follow the repo for breaking changes.
- **Live IEX TOPS via Databento was deprecated February 1, 2025** and replaced by EQUS.MINI. Older guides referencing free Databento IEX live data are obsolete.
- **Blue Sky Data (the IEX Cloud successor)** is too new to evaluate as a production data source as of May 2026.
- **Discord4J 3.2.6 is on the older 3.2 branch.** Nothing in this plan depends on upgrading it, but be aware that newer versions exist if you ever hit reconnection bugs that intersect with the market-data stream.