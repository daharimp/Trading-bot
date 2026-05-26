# Trading Bot Roadmap

Staged upgrade plan from a single-user IEX-fed bot with in-memory state to a SIP-fed, quote-aware, persistent bot with a closing feedback loop. Kraken crypto integration added alongside M1.

Source: research doc "Discord Trading Bot Upgrade Plan for Real-Time Data, Better Fills, and Persistent Memory (May 2026)".

See [ISSUES.md](./ISSUES.md) for the full paste-ready issue list.

## Milestones

| Milestone | Theme | Cost | Status | Issues |
|---|---|---|---|---|
| **M0** | Quick wins | Free | ✅ Done | 4 |
| **M1** | SIP + quote-aware execution + Kraken crypto | $99/mo | ✅ Done | 3 + crypto |
| **M2** | Persistence (SQLite on Fly Volume) | ~$0.15/mo (volume) | 🔜 Next | 6 |
| **M3** | Performance feedback loop | Free | 🔜 After 20+ trades | 2 |

Total: 15 issues + crypto integration.

## M0 — Quick wins (free) ✅

1. ✅ Eliminate the duplicate bar fetch (`AnalysisService` fetches bars twice — once for analysis, once for the chart renderer).
2. ✅ Batch overnight watchlist bars into a single multi-symbol Alpaca request.
3. ✅ Add intended-vs-fill slippage logging (in-memory dictionary, dumped to logs).
4. ✅ Replace overnight GTC limit pegged to last night's close with a gap-check + `time_in_force=opg` for the open auction.

## M1 — SIP upgrade + quote-aware execution + Kraken crypto ✅

Subscribe to Alpaca Algo Trader Plus and rewrite order placement to be quote-aware. Kraken added as the crypto data and execution layer.

5. ✅ Flip Alpaca data feed from IEX to SIP (env var + code path).
6. ✅ Fetch latest quote (`/v2/stocks/{symbol}/quotes/latest`) immediately before every order placement.
7. ✅ Switch from raw limit-at-entry to marketable-limit pricing (cross NBBO by 1–2 ticks with a slippage cap), `time_in_force=day` for intraday entries.

### Kraken crypto integration (added in M1) ✅

- ✅ `KrakenClient` — public OHLC bars + Ticker quotes (no auth), private HMAC-SHA512 signed order placement.
- ✅ `AlpacaClient.getBars()` routes crypto tickers to `KrakenClient.getBars()` when wired; falls back to Alpaca v1beta3.
- ✅ `AnalysisService.prefetchStockBars()` splits stock (Alpaca batch) vs crypto (Kraken individual) for overnight batch.
- ✅ `WatchlistScheduler.autoPlace()` uses `KrakenClient.getLatestQuote()` for crypto gap-checks.
- ✅ `OrderManager` routes crypto tickers to `KrakenClient.placeOrder()` — two-order bracket (entry+stop-loss close, then separate GTC TP limit). Stocks stay on Alpaca.
- ✅ Crypto OPG auto-play falls back to Kraken GTC bracket (opening auctions don't apply to 24/7 crypto).
- ✅ `TechnicalAnalyst` system prompt covers equities and crypto assets.
- ✅ `KRAKEN_API_KEY` / `KRAKEN_API_SECRET` optional env vars — data works without creds, order placement requires them.

Supported symbols: BTC, ETH, SOL, DOGE, AVAX, MATIC, LINK, UNI, ADA, XRP, LTC, BCH (+ `/USD` slash forms).

## M2 — Persistence (SQLite on Fly Volume) 🔜

Replace in-memory state (`SessionStore` ConcurrentHashMap, scheduler watchlist) with SQLite-on-volume + JDBI 3 + Flyway. Schema covers watchlist, analyses, setups, orders, fills, position outcomes.

8. Provision a 1 GB Fly Volume and mount it at `/data`.
9. Add Maven deps (sqlite-jdbc, jdbi3-core, flyway-core, HikariCP) and wire the connection pool at boot.
10. Write `V1__init.sql` schema (watchlist, analyses, setups, orders, fills, position_outcomes, setup_performance view).
11. Migrate `SessionStore` to a DB-backed DAO.
12. Migrate the `WatchlistScheduler` in-memory watchlist to the `watchlist` table.
13. Wire Alpaca trade-updates stream into the `fills` table; reconcile open orders on boot.

**Note:** Kraken order tracking. Once M2 lands, Kraken txids should be stored in the `orders` table alongside Alpaca order IDs so `!positions` can surface both brokers.

## M3 — Feedback loop 🔜

Once enough closed trades exist, close the loop by feeding empirical hit-rate back into the LLM prompt.

14. Inject `setup_performance` stats into the Claude technical-analysis prompt.
15. Add `!stats` Discord command that formats the `setup_performance` view.

## Decision thresholds (when to deviate)

- Overnight slippage logs (M0 #3) show > 15 bps consistent drag → evaluate IBKR Pro for execution.
- Watchlist grows past ~500 active symbols on the SIP stream → revisit Polygon Advanced ($199).
- Need MBO/L3 microstructure → Databento Standard ($199).
- Need HA on persistence → drop SQLite-on-volume, move to Fly Managed Postgres (~$10–15/mo).
- Kraken unsupported symbol needed → check Kraken's `/0/public/AssetPairs` and add to `PAIR_MAP` in `KrakenClient`.

## Out of scope

- Multi-tenant conversion for friends (covered by a separate research doc).
- IBKR or Polygon migration (kept as a threshold-triggered option, not default).
- Kraken WebSocket fills stream (deferred to M2 scope — use REST polling until persistence exists).
