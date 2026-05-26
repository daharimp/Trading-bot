# Trading Bot Roadmap

Staged upgrade plan from a single-user IEX-fed bot with in-memory state to a SIP-fed, quote-aware, persistent bot with a closing feedback loop.

Source: research doc "Discord Trading Bot Upgrade Plan for Real-Time Data, Better Fills, and Persistent Memory (May 2026)".

See [ISSUES.md](./ISSUES.md) for the full paste-ready issue list.

## Milestones

| Milestone | Theme | Cost | Target | Issues |
|---|---|---|---|---|
| **M0** | Quick wins | Free | This week | 4 |
| **M1** | SIP + quote-aware execution | $99/mo | This month | 3 |
| **M2** | Persistence (SQLite on Fly Volume) | ~$0.15/mo (volume) | 2–4 weeks | 6 |
| **M3** | Performance feedback loop | Free | After 20+ closed trades | 2 |

Total: 15 issues.

## M0 — Quick wins (free, do first)

Surface measurement data and stop the most obvious foot-guns before spending money on the SIP feed.

1. Eliminate the duplicate bar fetch (`AnalysisService` fetches bars twice — once for analysis, once for the chart renderer).
2. Batch overnight watchlist bars into a single multi-symbol Alpaca request.
3. Add intended-vs-fill slippage logging (in-memory dictionary, dumped to logs) — needed for the M1 cost/benefit decision.
4. Replace overnight GTC limit pegged to last night's close with a gap-check + `time_in_force=opg` for the open auction.

## M1 — SIP upgrade + quote-aware execution ($99/mo)

Subscribe to Alpaca Algo Trader Plus and rewrite order placement to be quote-aware.

5. Flip Alpaca data feed from IEX to SIP (env var + code path).
6. Fetch latest quote (`/v2/stocks/{symbol}/quotes/latest`) immediately before every order placement.
7. Switch from raw limit-at-entry to marketable-limit pricing (cross NBBO by 1–2 ticks with a slippage cap), `time_in_force=day` for intraday entries.

## M2 — Persistence (SQLite on Fly Volume)

Replace in-memory state (`SessionStore` ConcurrentHashMap, scheduler watchlist) with SQLite-on-volume + JDBI 3 + Flyway. Schema covers watchlist, analyses, setups, orders, fills, position outcomes.

8. Provision a 1 GB Fly Volume and mount it at `/data`.
9. Add Maven deps (sqlite-jdbc, jdbi3-core, flyway-core, HikariCP) and wire the connection pool at boot.
10. Write `V1__init.sql` schema (watchlist, analyses, setups, orders, fills, position_outcomes, setup_performance view).
11. Migrate `SessionStore` to a DB-backed DAO.
12. Migrate the `WatchlistScheduler` in-memory watchlist to the `watchlist` table.
13. Wire Alpaca trade-updates stream into the `fills` table; reconcile open orders on boot.

## M3 — Feedback loop

Once enough closed trades exist, close the loop by feeding empirical hit-rate back into the LLM prompt.

14. Inject `setup_performance` stats into the Claude technical-analysis prompt.
15. Add `!stats` Discord command that formats the `setup_performance` view.

## Decision thresholds (when to deviate)

- Overnight slippage logs (M0 #3) show > 15 bps consistent drag → evaluate IBKR Pro for execution.
- Watchlist grows past ~500 active symbols on the SIP stream → revisit Polygon Advanced ($199).
- Need MBO/L3 microstructure → Databento Standard ($199).
- Need HA on persistence → drop SQLite-on-volume, move to Fly Managed Postgres (~$10–15/mo).

## Out of scope

- Multi-tenant conversion for friends (covered by a separate research doc).
- IBKR or Polygon migration (kept as a threshold-triggered option, not default).
