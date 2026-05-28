# Jupiter Perps Integration — Design Spec

**Date:** 2026-05-27
**Status:** Approved (design); pending spec review → implementation plan

## Goal

Let the trading bot open and manage **leveraged perpetual** positions on **Jupiter Perpetuals (Solana)** — manually and via the existing analysis/auto-play pipeline — with a stop-based, liquidation-aware risk model and a paper-trading mode so the whole thing can be exercised without risking funds.

## Why Jupiter (constraints)

The user is in **California (US)**. US-restricted CEX perp venues (Bybit, Kraken Futures was rejected) are not viable. Jupiter is permissionless/on-chain and the user is already set up on it. Jupiter Perps is **on-chain only** (no hosted order REST) and has **no Java SDK** — official SDKs are TypeScript and Rust. The bot is Java. This drives the architecture below.

## Architecture

One new runtime — a thin TypeScript sidecar — sits between the Java bot and Solana:

```
Java bot (analysis, scoring, risk engine, scheduler, Discord)
   │   localhost HTTP (JSON)
   ▼
TS sidecar (Node)  ── Jupiter Perps SDK + @solana/web3.js ──►  Solana RPC / Jupiter Perps program
   • owns the Solana wallet keypair (only component that signs)
   • paper mode: reads live prices, simulates, signs nothing
```

The Java bot remains the decision-maker and never holds the Solana private key. The sidecar exposes a small HTTP API and is the only component touching Solana.

### Modes

A `JUPITER_MODE` env var (`paper` | `live`), mirroring the existing `ALPACA_MODE` pattern:

- **paper** (default): sidecar reads Jupiter's live on-chain oracle/mark prices but submits **no transactions**. Opens/closes are simulated in-process (entry, mark-to-market PnL, funding, stop/TP/liquidation checks) against real prices. Zero funds at risk.
- **live**: sidecar builds, `simulateTransaction`-validates, signs, and submits the real Jupiter request transaction (the keeper then fulfills it).

There is no Jupiter Perps devnet, so `paper` mode (not a testnet) is the safe-testing mechanism.

## Components & interfaces

### `jup-sidecar/` (new, TypeScript)
A standalone Node service. Responsibilities:
- Load the Solana wallet keypair (from its own keyfile/env; never exposed to Java).
- **Reads:** current mark/oracle price per supported asset; open positions with size, entry, leverage, unrealized PnL, liquidation price.
- **Writes (live mode):** open position (Jupiter's 2-step request→keeper flow), close position, set on-chain TP/SL trigger orders. In paper mode these mutate an in-memory/persisted simulated book instead.
- Pre-submit `simulateTransaction` check in live mode; refuse to submit if simulation fails.

HTTP API (localhost only):

| Method/Path | Purpose | Request | Response |
|---|---|---|---|
| `GET /health` | liveness + mode | — | `{mode, wallet, rpcOk}` |
| `GET /perp/price?asset=SOL` | live mark price | — | `{asset, mark, oracle}` |
| `GET /perp/positions` | open positions | — | `[{asset, side, size, entry, leverage, markPrice, liqPrice, unrealizedPnl}]` |
| `POST /perp/open` | open (or simulate) | `{asset, side, collateralUsd, leverage, stopLoss, takeProfit}` | `{positionId, entry, liqPrice, mode}` |
| `POST /perp/close` | close (or simulate) | `{positionId}` | `{exitPrice, realizedPnl, reason, mode}` |

`mode` is echoed on every mutating response so the bot/Discord always shows paper vs live.

### `JupiterClient.java` (new)
Typed OkHttp client wrapping the sidecar API — the Jupiter analog of `KrakenClient`. Methods: `health()`, `getMarkPrice(asset)`, `getPositions()`, `openPosition(req)`, `closePosition(positionId)`. Returns small records. Tolerates the sidecar being down (clear error, no crash).

### `RiskProfile` (new)
A persistent setting with three presets, stored in config/DB and overridable per trade:

| Profile | Default leverage | Max leverage | Min liquidation buffer¹ | Composite-score gate² |
|---|---|---|---|---|
| conservative | 2x | 3x | 1.5× stop distance | ≥ 72 |
| moderate | 3x | 5x | 1.25× stop distance | ≥ 65 |
| aggressive | 5x | 10x | 1.1× stop distance | ≥ 60 |

¹ Liquidation price must be at least this multiple of the stop distance beyond entry, else the trade is rejected.
² Reuses the existing `SignalScorer` composite (the same gate concept as spot auto-play).

Discord: `!riskprofile <conservative|moderate|aggressive>` sets it; `!riskprofile` shows current.

### `PositionSizer` (extend)
Add liquidation-aware perp sizing alongside the existing spot method:
1. Size by stop-based $ risk: `riskBudget = equity * RISK_PER_TRADE_PCT/100 * convictionFactor`; `notional` derived so a stop-out loses `riskBudget`.
2. Required margin = `notional / leverage`; reject if margin > available collateral.
3. Compute liquidation price for `notional`/`leverage`; **reject if liquidation is within `minLiquidationBuffer × stopDistance` of entry** (stop must trigger before liquidation).

### `SignalScorer` / analysis (extend)
When analyzing for a perp, given the chosen/default leverage + risk profile, the output additionally reports: liquidation price, liquidation distance vs stop, required margin, and a re-rate/REJECT if the leverage makes the stop unsafe per the profile. The LLM technical prompt gains a short leverage/risk-profile context line.

### Discord commands
- `!perp SOL LONG lev=3` — open a Jupiter perp (uses risk-profile default leverage if `lev=` omitted; explicit `lev=` overrides up to the profile max). Honors `JUPITER_MODE`.
- `!perp close SOL` — close an open perp.
- `!riskprofile [preset]` — get/set risk profile.
- `!positions` — extended to show Jupiter perp positions (paper or live) with leverage, liq price, uPnL.

### Outcome recording (Jupiter analog of the parked spot OutcomeTracker)
Poll `GET /perp/positions`; when a tracked perp position disappears (or the sidecar reports a close), record realized PnL into `position_outcomes` with conviction (reusing the Phase-4 schema/feedback loop). Paper closes are recorded too, tagged so they're distinguishable from live.

## Data flow (open a perp, paper mode)

1. `!perp SOL LONG lev=3` → `DiscordListener` → `AnalysisService`/order path.
2. Bot fetches mark price via `JupiterClient.getMarkPrice`, runs `SignalScorer` + `PositionSizer` (size, margin, liq-buffer check) under the active `RiskProfile`.
3. If accepted, bot calls `JupiterClient.openPosition(...)`; sidecar (paper) records a simulated position at the live mark and returns `{entry, liqPrice, mode:"paper"}`.
4. Bot confirms to Discord, marking **PAPER**. Outcome poller tracks it; on simulated stop/TP/close it records the outcome.

Live mode is identical except the sidecar `simulateTransaction`-checks then submits the real request tx.

## Supported assets

Jupiter Perps supported set only: **SOL, ETH, BTC**. Unsupported tickers are rejected with a clear message.

## Exits

Use Jupiter's **on-chain TP/SL trigger orders**, set by the sidecar at open. In paper mode the sidecar simulates these against live marks. The outcome poller is a backstop in case a trigger doesn't fire.

## Wallet / key / security

- A **dedicated Solana keypair** used only by the bot; collateral held as **USDC** on Solana.
- The private key is loaded **only by the sidecar** from its own keyfile/env (`SOLANA_KEYPAIR_PATH`), never passed to or logged by the Java bot.
- Sidecar binds to **localhost only**. No inbound exposure.
- A key compromise is contained to the sidecar service.

## Configuration (new env)

- `JUPITER_MODE` = `paper` (default) | `live`
- `JUP_SIDECAR_URL` = `http://127.0.0.1:8787`
- `SOLANA_RPC_URL` = mainnet RPC endpoint (sidecar)
- `SOLANA_KEYPAIR_PATH` = path to the wallet keyfile (sidecar only)
- `RISK_PROFILE` = `conservative` | `moderate` | `aggressive` (default `moderate`)
- Existing `RISK_PER_TRADE_PCT` / `MAX_PORTFOLIO_RISK_PCT` reused.

## Phasing (each independently shippable + paper-testable)

- **P1 — Sidecar + reads (no signing):** `jup-sidecar` skeleton, health/price/positions endpoints, `JupiterClient.java`, `!positions` shows Jupiter state. Proves connectivity and price reads with zero risk.
- **P2 — Paper trading + risk engine:** `JUPITER_MODE=paper` simulated `!perp` open/close, `PositionSizer` liquidation-aware sizing, `RiskProfile`, `!riskprofile`. Then add **live** execution behind the flag (with `simulateTransaction` pre-check + tiny-position smoke test).
- **P3 — Analysis + auto-play + outcomes:** leverage/risk-profile-aware `SignalScorer` output, opt-in perps auto-play (gated on composite conviction + profile leverage cap), Jupiter outcome recording into `position_outcomes`.

## Testing / verification

No Jupiter devnet exists, so:
- **Paper mode** is the primary safe test: real Jupiter prices, simulated fills/PnL/liquidation, no tx.
- **`simulateTransaction`** validates the real live transaction compiles/executes before any live submit.
- **Tiny live position** as the final smoke test before trusting size.
- Sidecar gets its own minimal test (TS) for the simulation/PnL math; Java side verified by running against the sidecar in paper mode.

## Error handling

- Sidecar down/unreachable → `JupiterClient` returns a clear error; commands fail safe (no order), bot keeps running.
- Live `simulateTransaction` failure → refuse to submit, report why.
- Liquidation-buffer or margin check fails → reject with the specific reason.
- RPC/keeper timeout on a live open → report unknown state and surface the position via the next `/perp/positions` poll rather than blind-retrying.

## Out of scope (YAGNI)

- Non-Jupiter venues; spot-on-Solana swaps.
- Assets beyond SOL/ETH/BTC.
- Cross-margin/portfolio-margin modeling beyond the single per-trade margin + portfolio risk cap already present.
- A GUI for the sidecar; it's a headless localhost service.

## Relationship to other work

- Reuses the **Phase-4 `position_outcomes` schema + `setup_performance` feedback loop** (currently parked mid-implementation) for perp outcomes. Perps outcome recording should land after that writer exists, or share its DAO.
- Independent of the stock-equity-accessor gap noted for spot risk sizing.
