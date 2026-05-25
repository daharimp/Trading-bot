# Trading Bot

A Java Discord bot that performs multi-timeframe technical and fundamental analysis on US equities and crypto — then lets you place bracket orders directly from Discord.

Runs 24/7 on Fly.io. Never depends on your machine being awake.

---

## How It Works

```
Discord !analyze AAPL
         │
         ▼
   Alpaca Markets
   (OHLCV bars: 5m / 15m / 1H / 4H / 1D)
         │
         ▼
   TA4J Rules Engine
   (EMA stacks, RSI, ATR, volume-confirmed crosses)
         │
         ├──────────────────────────────────────┐
         ▼                                      ▼
   Claude Sonnet (Technical)          Alpha Vantage + OpenRouter
   Validates/rewrites candidate       P/E, EPS, margins, earnings
   setups with star ratings           rated by configurable LLM
         │                                      │
         └──────────────┬────────────────────────┘
                        ▼
              Discord Response
              Chart image + chunked analysis
                        │
                        ▼
              !pick 1 qty=10
              Places bracket order on Alpaca
              (entry limit + stop + take-profit)
```

---

## Features

- **5-Timeframe Analysis** — 5m, 15m, 1H, 4H, 1D bars from Alpaca
- **Rules-Based Setup Generation** — EMA trend continuation, RSI extremes, volume-confirmed EMA crosses
- **Claude Technical Review** — Validates and enriches rule-engine setups with macro context, divergences, and star ratings
- **Fundamental Analysis** — P/E, EPS, margins, earnings via Alpha Vantage; rated by a configurable LLM on OpenRouter
- **Parallel Execution** — Technical and fundamental pipelines run concurrently
- **Chart Rendering** — Candlestick chart attached to the first Discord message
- **Bracket Order Placement** — `!pick N qty=X` places entry limit + stop-loss + take-profit on Alpaca
- **Watchlist Scheduler** — Runs overnight analysis at a configurable time (default 23:00 ET) and auto-places HIGH conviction setups
- **Crypto Support** — `!analyze BTC/USD` via Alpaca's v1beta3 crypto endpoint (no fundamentals for crypto)
- **Paper / Live Toggle** — Switch between Alpaca paper and live trading via `ALPACA_MODE`

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Discord | Discord4J 3.2.6 |
| Market Data | Alpaca Markets API (v2 stocks, v1beta3 crypto) |
| Fundamental Data | Alpha Vantage API |
| Technical Indicators | TA4J |
| Technical LLM | Anthropic Claude (default: `claude-sonnet-4-6`) |
| Fundamental LLM | OpenRouter (default: `tencent/hunyuan-3-preview`) |
| HTTP Client | OkHttp |
| JSON | Gson |
| Env Config | dotenv-java |
| Build | Maven (fat jar via shade plugin) |
| Container | Docker (eclipse-temurin:21-jre-jammy, 107 MB) |
| Hosting | Fly.io (shared-cpu-1x, 512 MB, `iad` region) |

---

## Discord Commands

| Command | Description |
| --- | --- |
| `!analyze AAPL` | Full technical + fundamental analysis with chart |
| `!analyze BTC/USD` | Crypto analysis (no fundamentals) |
| `!pick 1 qty=10` | Place setup #1 from the last analysis as a bracket order |
| `!watch AAPL TSLA` | Add tickers to overnight watchlist |
| `!watch AAPL auto` | Add with auto-place for HIGH conviction setups at schedule time |
| `!unwatch AAPL` | Remove ticker from watchlist |
| `!watchlist` | Show current watchlist |
| `!runanalysis` | Trigger overnight analysis immediately |
| `!play AAPL LONG e=182.50 s=179 t=189 qty=10` | Manual bracket order |
| `!positions` | Show open positions and pending orders |
| `!cancel ORDER_ID` | Cancel a pending order |
| `!help` | Show command reference |

---

## Setup

### 1. Clone

```bash
git clone https://github.com/daharimp/trading-bot.git
cd trading-bot
```

### 2. Configure secrets

```bash
cp .env.example .env
```

Edit `.env`:

```env
DISCORD_BOT_TOKEN=        # Bot token from Discord Developer Portal
DISCORD_CHANNEL_NAME=     # Channel name to restrict to (blank = all channels)

ALPACA_API_KEY=           # Alpaca key
ALPACA_API_SECRET=        # Alpaca secret
ALPACA_MODE=paper         # paper | live

ANTHROPIC_API_KEY=        # Claude API key (technical analysis)
ALPHA_VANTAGE_API_KEY=    # Alpha Vantage key (fundamental data)
OPENROUTER_API_KEY=       # OpenRouter key (fundamental LLM)

FUNDAMENTAL_LLM_MODEL=tencent/hunyuan-3-preview   # Any OpenRouter model
TECHNICAL_LLM_MODEL=claude-sonnet-4-6              # Any Anthropic model
ANALYSIS_SCHEDULE_TIME=23:00                        # ET, 24h format
DEFAULT_QTY=1
```

> **Discord setup:** Enable **Message Content Intent** in the Discord Developer Portal under your bot's settings (Bot → Privileged Gateway Intents).

### 3. Build

```bash
mvn package -q
```

### 4. Run locally

```bash
java -jar target/trading-bot-1.0-SNAPSHOT.jar
```

Or with Docker Desktop:

```bash
docker build -t trading-bot .
docker run --rm --env-file .env --name trading-bot trading-bot
```

---

## Deploy to Fly.io (Production)

The bot runs as a persistent background worker — no HTTP endpoints, no scale-to-zero.

```bash
# One-time setup
flyctl auth login
flyctl launch --no-deploy --name your-app-name
flyctl secrets import < .env

# Deploy
flyctl deploy

# Verify
flyctl logs --app your-app-name | grep "Bot online"
```

### Operations reference

```bash
# Status
flyctl status --app your-app-name

# Live logs
flyctl logs --app your-app-name

# Restart
flyctl machine restart --app your-app-name

# Redeploy after code changes
mvn package -q && flyctl deploy --app your-app-name

# Update a secret
flyctl secrets set ANTHROPIC_API_KEY=new_value --app your-app-name

# SSH into running container
flyctl ssh console --app your-app-name
```

---

## Project Structure

```
src/main/java/com/tradingbot/
├── Main.java                        # Entry point, dependency wiring
├── alpaca/
│   └── AlpacaClient.java           # OHLCV bars (stocks + crypto), order placement
├── analysis/
│   ├── AnalysisService.java        # Orchestrates parallel TA + FA pipelines
│   ├── IndicatorEngine.java        # TA4J wrapper (EMA, RSI, ATR, volume)
│   ├── TradeIdeaGenerator.java     # Rules-based setup candidates
│   ├── TechnicalAnalyst.java       # Claude setup validation & enrichment
│   └── FundamentalAnalyst.java     # OpenRouter LLM fundamental rating
├── chart/
│   └── ChartRenderer.java          # Candlestick chart → PNG bytes
├── discord/
│   ├── DiscordListener.java        # Command router, chunked message sending
│   ├── DiscordNotifier.java        # Proactive notifications (scheduler)
│   └── SessionStore.java           # Per-channel setup state for !pick
├── fundamental/
│   └── FundamentalDataClient.java  # Alpha Vantage OVERVIEW + EARNINGS fetch
├── model/
│   ├── TradeIdea.java              # Setup model (entry, stop, target, R:R, conviction)
│   └── FundamentalData.java        # Fundamental metrics model
├── order/
│   └── OrderManager.java           # Bracket order placement + position/order queries
└── scheduler/
    └── WatchlistScheduler.java     # Nightly analysis cron runner
```

---

## Rules Engine — Setup Types

| Setup | Condition | Direction |
| --- | --- | --- |
| EMA Trend Continuation | 9 > 21 > 50 (or inverse), RSI not extreme | LONG / SHORT |
| RSI Oversold Bounce | RSI < 32, price above EMA50 | LONG |
| RSI Overbought Fade | RSI > 68, price below EMA50 | SHORT |
| Volume-Confirmed EMA Cross | EMA9/21 cross within 3 bars, volume > 1.3× avg | LONG / SHORT |

Minimum 1.5:1 R:R required to forward a candidate to Claude.

---

## API Usage per `!analyze`

Each `!analyze` call makes the following requests:

| Service | Calls | Notes |
| --- | --- | --- |
| Alpaca | 5 | One bar request per timeframe (5m/15m/1H/4H/1D). Stocks retry on `sip` then `iex` feed. |
| Anthropic (Claude) | 1 | Single call with full multi-timeframe indicator snapshot + candidate setups |
| Alpha Vantage | 2 | `OVERVIEW` + `EARNINGS` endpoints (skipped for crypto) |
| OpenRouter | 1 | Single fundamental rating call (skipped for crypto) |

**Total: 9 calls for equities, 6 for crypto.**

---

## Limitations

- **Stateless watchlist** — `!watch` entries are held in memory and reset on restart. No persistence layer yet.
- **Alpha Vantage free tier** — 25 req/day, 5 req/min. Rapid successive `!analyze` calls may hit the rate limit.
- **Crypto** — No fundamental analysis. Technical analysis works via Alpaca's crypto bars endpoint.
- **Not financial advice.** For informational and educational purposes only.

---

## License

MIT
