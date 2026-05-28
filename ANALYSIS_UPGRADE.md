# Analysis Upgrade — Local Build & Verify Handoff

This branch (`claude/bot-analysis-improvement-xjhHZ`) upgrades the bot's analysis from a thin
EMA/RSI/ATR pipeline into a multi-timeframe **decision engine** with **risk-based sizing** and a
**backtest harness**. All changes are compute-only — no new dependencies, no new paid data
(ta4j 0.15 already ships every indicator used; bars come from the existing Alpaca/Kraken feeds).

The code was written in a cloud container **without compiling**. Your first job locally is to
build it, fix any compile errors (most likely the ta4j constructor signatures listed below), then
verify behavior. Feed this file to Claude Code locally and say: *"build this, fix compile errors,
then walk me through the verification steps."*

## Build

```bash
mvn package -q
```

This is the CLAUDE.md pre-merge gate and must pass before committing further work.

## ta4j 0.15 API signatures to confirm if the build fails

These are the only spots where the exact ta4j 0.15 signature is uncertain. If `mvn package`
errors, check these first in `src/main/java/com/tradingbot/analysis/IndicatorEngine.java`:

1. **ADX** — used as `new ADXIndicator(series, 14, 14)` (diBarCount, adxBarCount). If 0.15 only has
   the 2-arg form, change to `new ADXIndicator(series, 14)`.
2. **Bollinger upper/lower** — used as
   `new BollingerBandsUpperIndicator(bbMiddle, sd20, DecimalNum.valueOf(2))`. If the 3-arg (with `Num k`)
   form is absent, drop the third arg (defaults to k=2).
3. **VWAP** — `new VWAPIndicator(series, 14)`. Confirm the package is
   `org.ta4j.core.indicators.volume.VWAPIndicator`.
4. **OBV** — `new OnBalanceVolumeIndicator(series)` from `org.ta4j.core.indicators.volume`.
5. **Num comparisons** — `isLessThanOrEqual` / `isGreaterThanOrEqual` are used in
   `macdBullishCross`/`macdBearishCross`. If missing in 0.15, invert with `!isGreaterThan(...)`.
6. **getSubSeries** — `Backtester` calls `series.getSubSeries(begin, i + 1)` (end-exclusive).
   Confirm semantics; the backtest depends on no look-ahead (sub-series must contain only bars ≤ i).

## What changed (by file)

**Phase 1 — richer indicators** (`analysis/IndicatorEngine.java`)
Added MACD (+signal/histogram & crosses), Bollinger (upper/mid/lower, %B, bandwidth, squeeze),
Stochastic %K/%D, ADX + DI, OBV (+slope), VWAP, RSI bullish/bearish divergence, and a HH/HL/LH/LL
market-structure classifier. All surfaced to the LLM in `analysis/TechnicalAnalyst.java`
(`buildUserPrompt`).

**Phase 2 — decision engine** (`analysis/SignalScorer.java` NEW, `model/TradeIdea.java`,
`analysis/FundamentalAnalyst.java`, `analysis/AnalysisService.java`)
- `SignalScorer` produces a 0-100 composite per idea = weighted blend of a directional **technical
  score** (trend/momentum/volume/structure/divergence), a **multi-timeframe alignment** score
  (higher TFs weighted more), and a **fundamental score**. It tags a regime (TRENDING/RANGING/
  VOLATILE), assigns 1-5 stars, and **overrides** the idea's conviction with the composite-derived
  one — so all downstream gating uses the fused score.
- `FundamentalAnalyst` now emits a parseable `FUNDAMENTAL SCORE: <-100..100>` line and returns a
  `FundamentalResult(summary, score)` record. Fundamentals are weighted only for swing TFs (H4/D1),
  ~zero for intraday/crypto.
- `AnalysisService` scores every idea, sorts best-first, and shows the breakdown in Discord.

**Phase 3 — risk sizing** (`order/PositionSizer.java` NEW, `AnalysisService`, `Main`,
`discord/DiscordListener.java`, `scheduler/WatchlistScheduler.java`, `.env.example`)
- `qty = floor(equity * RISK_PER_TRADE_PCT/100 * convictionFactor / |entry-stop|)`
  (HIGH=1.0, MEDIUM=0.5, LOW=0.25).
- Each idea is annotated with a suggested qty (shown in analysis + pick menu).
- `!pick N` now uses the risk-sized qty (explicit `qty=` overrides); overnight auto-play uses it too,
  falling back to `DEFAULT_QTY`.
- New env vars: `RISK_PER_TRADE_PCT` (default 1.0), `MAX_PORTFOLIO_RISK_PCT` (default 5.0).
- **Equity source caveat:** wired to `AccountMonitor.getTotalUsdValue()` which is **Kraken USD only**
  today — so risk sizing is live for crypto. For stocks, add an Alpaca account-equity accessor
  (`GET /v2/account` → `equity`) and switch the supplier in `Main` to choose Alpaca vs Kraken by
  ticker type. Until then, stock ideas get qty 0 and fall back to manual/`DEFAULT_QTY`.

**Phase 5 — backtesting** (`backtest/Backtester.java` NEW, `AnalysisService.runBacktest`,
`DiscordListener` `!backtest` command)
- No-look-ahead replay: at each bar, rebuild the sub-series up to that bar, run the rules engine,
  simulate the trade forward (conservative stop-before-target), aggregate win rate / avg R /
  expectancy / profit factor / max drawdown (in R).
- Command: `!backtest BTC 1H` (timeframes 5m 15m 1H 4H 1D; default 1H).

**Phase 4 — feedback loop (PARTIAL)** (`db/PerformanceDao.java` NEW, `TechnicalAnalyst`, `Main`)
- `PerformanceDao` reads the existing (previously dormant) `setup_performance` view and injects a
  realized win-rate fragment into the technical prompt — guarded so it adds nothing until outcomes
  exist. It also exposes `recordOutcome(...)` to write the `position_outcomes` table.
- **REMAINING WORK (do locally with runtime feedback):** wire the writer. When a bracket
  position closes, call `PerformanceDao.recordOutcome(...)`. Best home is alongside the existing
  fill polling in `order/SlippageTracker.java` (Alpaca: detect the stop/target leg filling and the
  position going flat) and the Kraken close path. This needs live order data to get right, which is
  why it's left for local iteration. Once wired, the prompt win-rate context activates automatically.

## Verify (market is closed → use crypto via Kraken + historical bars)

1. `mvn package -q` compiles clean.
2. Run the bot (`/runtrading` or `java -jar target/trading-bot-1.0-SNAPSHOT.jar`).
3. `!analyze BTC/USD` — confirm the analysis now shows MACD, Bollinger (+squeeze), ADX/DI,
   Stochastic, VWAP, OBV slope, market structure, divergence per timeframe; each setup shows a
   `Score X/100` with stars, a regime tag, and a risk-sized suggested qty; setups are ordered
   best-first.
4. `!analyze AAPL` (after open, or expect "data unavailable" while closed) — confirm the fundamental
   block still renders and that a non-NaN `FUNDAMENTAL SCORE` visibly moves the composite vs a
   technicals-only run.
5. `!backtest BTC 1H` and `!backtest BTC 1D` — confirm win rate / avg R / expectancy / profit factor
   / max drawdown print on real history.
6. `!pick 1` (no qty) — confirm it places using the risk-sized qty; `!pick 1 qty=5` still overrides.
7. Sanity: confirm overnight auto-play and `!pick` gate on the **composite** conviction (the score
   overrides the raw rules/LLM conviction).

## Tuning knobs

- Score weights & thresholds: `SignalScorer` (`wTech/wMtf/wFund`, `convictionFor`, `stars`,
  `TF_WEIGHT`, regime cutoffs).
- Risk %: `RISK_PER_TRADE_PCT` / `MAX_PORTFOLIO_RISK_PCT` in `.env`.
- Indicator periods: `IndicatorEngine` constructor.
- Use `!backtest` to validate any change to the rules engine or scoring before trusting it live.
