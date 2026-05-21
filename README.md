# 📊 Trading Bot

A Java-based Discord trading assistant that performs **multi-timeframe technical analysis** and **fundamental analysis** on any US equity ticker — powered by GPT-4o, Alpaca Markets, and Alpha Vantage.

Drop a ticker in your Discord channel. Get institutional-grade analysis back in seconds.

---

## How It Works

```
Discord !analyze AAPL
         │
         ▼
   Alpaca Markets
   (OHLCV bars across 5m / 15m / 1H / 4H / 1D)
         │
         ▼
   TA4J Rules Engine
   (EMA stacks, RSI, ATR, volume-confirmed crosses)
         │
         ├──────────────────────────────────┐
         ▼                                  ▼
   GPT-4o (Technical)            Alpha Vantage + OpenRouter
   Validates/rewrites             (Fundamental data → Gemini/LLM)
   candidate setups               P/E, EPS, margins, earnings
         │                                  │
         └──────────────┬───────────────────┘
                        ▼
              Discord Response
              (Setups, levels, rationale, fundamental rating)
```

---

## Features

- **5-Timeframe Analysis** — 5m, 15m, 1H, 4H, 1D bars fetched from Alpaca
- **Rules-Based Setup Generation** — EMA trend continuation, RSI oversold/overbought, volume-confirmed EMA crosses
- **GPT-4o Technical Review** — Validates, enriches, or rejects rule-engine setups with macro context, divergences, and pattern recognition
- **Fundamental Analysis** — P/E ratio, EPS, margins, earnings history, analyst targets via Alpha Vantage; analyzed by a configurable LLM via OpenRouter
- **Parallel Execution** — Technical and fundamental pipelines run concurrently for fast turnaround
- **Discord Integration** — Fully formatted output with entry, stop, target, R:R, conviction badge, and rationale
- **Paper / Live Mode Toggle** — Switch between Alpaca paper and live endpoints via `.env`

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Discord | Discord4J |
| Market Data | Alpaca Markets API |
| Fundamental Data | Alpha Vantage API |
| Technical Indicators | TA4J |
| Technical LLM | OpenAI GPT-4o |
| Fundamental LLM | OpenRouter (default: `google/gemini-flash-1.5`) |
| HTTP Client | OkHttp |
| JSON | Gson |
| Env Config | dotenv-java |
| Logging | SLF4J |

---

## Prerequisites

- Java 21+
- Maven 3.8+
- A Discord bot token with **Message Content Intent** enabled
- Alpaca Markets account (paper or live)
- Alpha Vantage API key (free tier works; 5 req/min)
- OpenAI API key (GPT-4o access)
- OpenRouter API key

---

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/youruser/trading-bot.git
cd trading-bot
```

### 2. Configure environment variables

Copy the example env file and fill in your credentials:

```bash
cp .env.example .env
```

```env
# Discord
DISCORD_BOT_TOKEN=your_discord_bot_token
DISCORD_CHANNEL_NAME=signals          # Leave blank to respond in any channel

# Alpaca
ALPACA_API_KEY=your_alpaca_key
ALPACA_API_SECRET=your_alpaca_secret
ALPACA_MODE=paper                     # paper | live

# OpenAI (GPT-4o — technical analysis)
OPENAI_API_KEY=your_openai_key

# Alpha Vantage (fundamental data)
ALPHA_VANTAGE_API_KEY=your_alphavantage_key

# OpenRouter (fundamental LLM)
OPENROUTER_API_KEY=your_openrouter_key
FUNDAMENTAL_LLM_MODEL=google/gemini-flash-1.5   # Any OpenRouter model
```

### 3. Build

```bash
mvn clean package -q
```

### 4. Run

```bash
java -jar target/trading-bot.jar
```

---

## Usage

In your configured Discord channel, type:

```
!analyze AAPL
```

The bot will respond with a full analysis block:

```
📊 AAPL | $213.42 | RSI 54 | ATR 3.21
━━━━━━━━━━━━━━━━━━━━━━━━━
GPT-4o identified 2 setup(s):

── 1H ──
📈 LONG AAPL | `1H` | 🔥 HIGH
> Entry:      $213.42
> Stop Loss:  $209.80
> Target:     $220.66
> R:R:        1:2.0
> Bull EMA stack confirmed across 1H and 4H. Volume 1.4x above average on the cross. ⚠️ Trade invalidates on close below EMA21.

── 1D ──
📈 LONG AAPL | `1D` | ⚡ MEDIUM
> Entry:      $213.42
> Stop Loss:  $207.10
> Target:     $226.06
> R:R:        1:2.0
> Daily trend intact. RSI 54 — room to run. Watch for resistance near 52-week high. ⚠️ Earnings risk within 3 weeks.

━━━━━━━━━━━━━━━━━━━━━━━━━
📋 FUNDAMENTAL ANALYSIS

FUNDAMENTAL RATING: BUY
• Revenue grew 8% YoY — services segment accelerating and expanding margins
• P/E of 28x is in line with large-cap tech peers; not stretched given FCF profile
• Analyst 12-month target of $235 implies ~10% upside from current price
• Debt/equity manageable at 1.5x; strong buyback program provides price support
• Next earnings in ~3 weeks — binary event risk; size positions accordingly

⚠️ AI-generated analysis — not financial advice. Manage your own risk.
```

---

## Project Structure

```
src/main/java/com/tradingbot/
├── Main.java                          # Entry point, dependency wiring
├── alpaca/
│   └── AlpacaClient.java             # Fetches OHLCV bar data from Alpaca
├── analysis/
│   ├── IndicatorEngine.java          # TA4J wrapper (EMA, RSI, ATR, volume)
│   ├── TradeIdeaGenerator.java       # Rules-based setup candidates
│   ├── TechnicalAnalyst.java         # GPT-4o setup validation & enrichment
│   └── FundamentalAnalyst.java       # OpenRouter LLM fundamental rating
├── discord/
│   └── DiscordListener.java          # Event handler, orchestrates full pipeline
├── fundamental/
│   └── FundamentalDataClient.java    # Alpha Vantage OVERVIEW + EARNINGS fetch
└── model/
    ├── TradeIdea.java                 # Setup model (entry, stop, target, R:R)
    └── FundamentalData.java          # Fundamental metrics model
```

---

## Trade Setups — Rules Engine

The `TradeIdeaGenerator` identifies four setup types before passing candidates to GPT-4o:

| Setup | Condition | Direction |
|---|---|---|
| EMA Trend Continuation | 9 > 21 > 50 (or inverse), RSI not extreme | LONG / SHORT |
| RSI Oversold Bounce | RSI < 32, price above EMA50 | LONG |
| RSI Overbought Fade | RSI > 68, price below EMA50 | SHORT |
| Volume-Confirmed EMA Cross | EMA9/21 cross within 3 bars, volume > 1.3× avg | LONG / SHORT |

All setups must meet a minimum **1.5:1 R:R** to be forwarded to GPT-4o.

---

## Indicators

Computed by `IndicatorEngine` via TA4J at the latest bar of each timeframe:

- **EMA 9, 21, 50** — trend direction and stack alignment
- **RSI (14)** — momentum and extremes
- **ATR (14)** — volatility-based stop sizing
- **Volume** — current vs. 20-bar average ratio
- **Swing High / Low** — structural support/resistance over N bars

---

## Configuration Notes

- `DISCORD_CHANNEL_NAME` — Set to a channel name (e.g. `signals`) to restrict the bot to one channel. Leave blank to respond anywhere.
- `ALPACA_MODE` — Both `paper` and `live` use the same data endpoint (`data.alpaca.markets`). Set `live` for live trading account context.
- `FUNDAMENTAL_LLM_MODEL` — Any model available on OpenRouter (e.g. `anthropic/claude-3-haiku`, `mistralai/mixtral-8x7b-instruct`). Defaults to `google/gemini-flash-1.5`.
- Alpha Vantage free tier: 25 requests/day, 5 requests/minute. The client adds a 500ms delay between calls to stay within limits.

---

## Limitations & Disclaimer

- **Not financial advice.** This tool is for informational and educational purposes only.
- Analysis quality depends on the underlying LLM and the bar data available. Low-liquidity tickers may have incomplete data.
- GPT-4o has no memory of previous analyses. Each `!analyze` call is stateless.
- Alpha Vantage free tier rate limits may cause fundamental data failures during rapid successive requests.
- The bot does **not** place trades. It is a read-only analysis assistant.

---

## License

MIT
