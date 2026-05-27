# Live Crypto Trading — Go-Live Design Spec

**Date:** 2026-05-26  
**Status:** Draft — awaiting user approval  
**Goal:** Deploy $200 into live crypto trading (HYPE, SOL, BTC) via Kraken with safety controls and manual exit capability.

---

## Context

The trading bot is fully functional in paper mode, deployed 24/7 on Fly.io. The user wants to go live with $200 on crypto. Alpaca stays in paper mode. Before funding, a 48-hour paper run validates the bot's edge on target tickers. The user needs:

1. HYPE added to supported crypto pairs
2. An account-level circuit breaker that stops trading if balance drops below $100
3. Manual exit control via Discord (`!exit`)
4. Live position view with unrealized P&L (`!positions` update)

---

## Architecture

No new major subsystems. All changes are additive to existing classes:

```
KrakenClient.java          ← add HYPE/USD pair
AccountMonitor.java        ← NEW: equity circuit breaker (Kraken balance polling)
OrderManager.java          ← check paused flag before placing any order
DiscordListener.java       ← add !exit and !resume command routing
KrakenClient.java          ← add getOpenPositions(), cancelAllOrders() methods
```

---

## Feature 1 — HYPE/USD Crypto Pair Support

**File:** `src/main/java/com/tradingbot/kraken/KrakenClient.java`

**Change:** Add `"HYPE"` to the `SUPPORTED_PAIRS` map (or equivalent routing constant) with Kraken pair format `HYPEUSD`.

**Behavior after change:**
- `!analyze HYPE` — runs full 5-timeframe TA + fundamental analysis
- `!watch HYPE auto` — adds HYPE to overnight watchlist with auto-order enabled
- `!play HYPE LONG e=0.38 s=0.34 t=0.44 qty=100` — places bracket order via Kraken

**Verification:** `!analyze HYPE` returns a chart and setup without errors.

---

## Feature 2 — Account Equity Circuit Breaker

**New file:** `src/main/java/com/tradingbot/monitor/AccountMonitor.java`

### Behavior
- Polls Kraken account balance every **5 minutes** via `KrakenClient.getAccountBalance()`
- Computes total USD value: USD cash + estimated crypto value at current prices
- If total drops below `MIN_EQUITY_THRESHOLD` (default `$100.00`, configurable via env var):
  1. Calls `KrakenClient.cancelAllOpenOrders()` — cancels all pending Kraken stop/TP orders (Alpaca paper orders are unaffected)
  2. Sets a shared `AtomicBoolean paused = true` flag
  3. Posts Discord alert: `⚠️ Balance dropped to $X.XX (below $100 threshold). All pending orders cancelled. Bot paused — use !resume to re-enable.`
- On startup, always checks balance before allowing any orders

### Integration with OrderManager
`OrderManager.placeOrder()` checks `AccountMonitor.isPaused()` before submitting. If paused, throws with message `"Bot is paused — account below minimum equity. Use !resume to re-enable."`

### !resume command
`DiscordListener` routes `!resume`:
- Calls `AccountMonitor.unpause()`
- Re-checks current balance; if still below threshold, warns but unpauses
- Responds: `✅ Bot resumed. Current balance: $X.XX`

### Config
```
MIN_EQUITY_THRESHOLD=100.00   # USD, defaults to 100 if not set
```

---

## Feature 3 — Manual Exit Control (!exit)

**File:** `src/main/java/com/tradingbot/discord/DiscordListener.java`  
**File:** `src/main/java/com/tradingbot/kraken/KrakenClient.java` (add `getOpenPositions()`, `closePosition()`)

### Commands

**`!exit SYMBOL`** — e.g., `!exit SOL`
1. Looks up open Kraken position for SYMBOL
2. Cancels any open orders for that pair (stop-loss, take-profit)
3. Places market sell for full position size
4. Responds: `Exited SOL — sold 0.52 SOL at ~$145.20 | Est. P&L: +$12.40`

**`!exit all`** — nuclear option
1. Fetches all open positions from Kraken
2. For each: cancel open orders → market sell
3. Responds with a summary table of all exits

### Error cases
- No open position for symbol → `No open position found for HYPE`
- Kraken API error → `Failed to exit SOL: [error message]. Check !positions and retry.`

---

## Feature 4 — Live Positions with Unrealized P&L (!positions update)

**File:** `src/main/java/com/tradingbot/discord/DiscordListener.java`  
**File:** `src/main/java/com/tradingbot/kraken/KrakenClient.java` (add `getOpenPositions()`)

### Current behavior
`!positions` shows Alpaca positions + pending orders only.

### Updated behavior
`!positions` shows:
- **Kraken open positions** (crypto): symbol, qty, avg entry price, current price, unrealized P&L ($), unrealized P&L (%)
- **Alpaca positions** (paper): unchanged, labeled "(paper)"
- **Account balance summary**: Total Kraken USD balance + estimated crypto value

**Example output:**
```
📊 Live Crypto Positions (Kraken)
SOL  | 0.52 @ $138.40 | Now: $145.20 | +$3.54 (+4.9%)
BTC  | 0.001 @ $68,200 | Now: $69,100 | +$0.90 (+1.3%)
Balance: $42.18 USD + ~$155.00 crypto = ~$197.18 total

📋 Paper Positions (Alpaca)
SPY  | 1 share @ $527.40 | Now: $529.10 | +$1.70 (+0.3%)
```

---

## Phase 2 — Go-Live Checklist (after 48-hour paper test)

Run in sequence after paper test passes:

1. **Fund Kraken** — deposit $200 USD
2. **Set Fly.io secrets:**
   ```
   fly secrets set \
     KRAKEN_API_KEY=your_live_key \
     KRAKEN_API_SECRET=your_live_secret \
     MIN_EQUITY_THRESHOLD=100.00
   ```
3. **Keep** `ALPACA_MODE=paper` — no change needed
4. `fly deploy`
5. Confirm "Bot online" in Discord
6. Seed watchlist:
   ```
   !watch HYPE auto
   !watch SOL auto
   !watch BTC auto
   ```
7. Verify `!positions` shows Kraken balance correctly

---

## Paper Test Checklist (48 hours before go-live)

Run immediately after deploying these features:

- [ ] `!analyze HYPE` returns chart + setup without error
- [ ] `!watch HYPE auto` persists to SQLite watchlist
- [ ] Overnight analysis (23:00 ET) fires and analyzes HYPE/SOL/BTC
- [ ] HIGH conviction setups auto-place in paper mode (Alpaca paper for stocks)
- [ ] `!positions` shows positions with P&L
- [ ] `!exit SOL` cancels orders and exits (in paper — test with `!play` first)
- [ ] Bot survives overnight without crashing (check Fly.io logs at 08:00)
- [ ] LLM costs stay under $1 for 48 hours (check OpenRouter dashboard)

---

## Feature 5 — Daily Trade Limit (15 trades/day)

**File:** `src/main/java/com/tradingbot/order/OrderManager.java`

### Behavior
- `OrderManager` tracks a daily trade counter (`AtomicInteger dailyTradeCount`)
- Counter resets to 0 at midnight ET each day (scheduled via `ScheduledExecutorService`)
- Before placing any order: if `dailyTradeCount >= 15`, reject with Discord message:
  `🚫 Daily trade limit reached (15/15). No new orders until midnight ET.`
- Counter increments on every successful order submission (entry orders only, not stop/TP legs)
- `!positions` output includes: `Trades today: 8/15`

### Config
```
MAX_DAILY_TRADES=15   # defaults to 15 if not set
```

---

## Files to Create / Modify

| File | Action | Notes |
|------|--------|-------|
| `src/main/java/com/tradingbot/kraken/KrakenClient.java` | Modify | Add HYPE pair, `getOpenPositions()`, `cancelAllOpenOrders()`, `closePosition()`, `getAccountBalance()` |
| `src/main/java/com/tradingbot/monitor/AccountMonitor.java` | **Create** | Circuit breaker, 5-min polling, paused flag |
| `src/main/java/com/tradingbot/order/OrderManager.java` | Modify | Check `AccountMonitor.isPaused()` before placing |
| `src/main/java/com/tradingbot/discord/DiscordListener.java` | Modify | Add `!exit`, `!resume` command routing; update `!positions` handler |
| `src/main/java/com/tradingbot/Main.java` | Modify | Wire `AccountMonitor` into startup, pass to `OrderManager` |
| `.env.example` | Modify | Document `MIN_EQUITY_THRESHOLD` |

---

## Verification (End-to-End)

1. Build: `mvn package -q` — no compile errors
2. Run locally: `java -jar target/trading-bot-1.0-SNAPSHOT.jar`
3. In Discord: `!analyze HYPE` — should return analysis + chart
4. In Discord: `!play HYPE LONG e=0.38 s=0.34 t=0.44 qty=10` — paper order placed
5. In Discord: `!positions` — shows HYPE position with P&L
6. In Discord: `!exit HYPE` — position closed, confirmation message
7. Lower `MIN_EQUITY_THRESHOLD` to a very high value in `.env` locally → trigger circuit breaker → verify Discord alert + `!resume` works
8. Deploy to Fly.io: `fly deploy` — "Bot online" in logs
