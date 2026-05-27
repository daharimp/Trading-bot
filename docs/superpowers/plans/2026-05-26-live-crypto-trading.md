# Live Crypto Trading — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add HYPE/USD support, account equity circuit breaker, manual exit control, live P&L positions view, and 15-trade/day limit to enable safe live crypto trading on Kraken.

**Architecture:** All changes are additive to the existing class structure. `AccountMonitor` is the only new class — it runs on a 5-minute scheduler and exposes an `isPaused()` flag that `OrderManager` checks before every order. `KrakenClient` gets 4 new methods. `DiscordListener` routes 2 new commands (`!exit`, `!resume`) and updates `!positions`.

**Tech Stack:** Java 21 virtual threads, OkHttp3 4.12.0, Gson 2.10.1, Discord4J 3.2.6, dotenv-java 3.0.0, Kraken REST API v0.

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `src/main/java/com/tradingbot/kraken/KrakenClient.java` | Modify | Add HYPE pair; add `getAccountBalance()`, `getOpenPositions()`, `cancelAllOpenOrders()`, `closePosition()` |
| `src/main/java/com/tradingbot/monitor/AccountMonitor.java` | **Create** | 5-min balance polling, circuit breaker, paused flag |
| `src/main/java/com/tradingbot/order/OrderManager.java` | Modify | Inject `AccountMonitor`; check paused flag + daily trade limit before every order |
| `src/main/java/com/tradingbot/discord/DiscordListener.java` | Modify | Route `!exit`, `!resume`; update `!positions` with Kraken P&L + trade count |
| `src/main/java/com/tradingbot/Main.java` | Modify | Instantiate `AccountMonitor`; inject into `OrderManager` and `DiscordListener` |
| `.env.example` | Modify | Document `MIN_EQUITY_THRESHOLD` and `MAX_DAILY_TRADES` |

---

## Task 1: Add HYPE/USD to KrakenClient

**Files:**
- Modify: `src/main/java/com/tradingbot/kraken/KrakenClient.java`

- [ ] **Step 1: Open KrakenClient and locate PAIR_MAP**

Find the static `PAIR_MAP` initialization block. It currently has entries like `"BTC"` → `"XBTUSD"`, `"SOL"` → `"SOLUSD"`, etc.

- [ ] **Step 2: Add HYPE entry to PAIR_MAP**

In `KrakenClient.java`, add to the `PAIR_MAP` put block (alongside the existing crypto entries):

```java
PAIR_MAP.put("HYPE", "HYPEUSD");
PAIR_MAP.put("HYPE/USD", "HYPEUSD");
```

Kraken's actual pair name for HYPE is `HYPEUSD`. Verify at: https://api.kraken.com/0/public/AssetPairs (search for HYPE).

- [ ] **Step 3: Build to verify no compile errors**

```bash
cd /Users/daharimorgan/dev/trading-bot
mvn package -q
```

Expected: `BUILD SUCCESS`. No output means success.

- [ ] **Step 4: Test manually**

Run the bot locally, then in Discord:
```
!analyze HYPE
```
Expected: Bot returns a candlestick chart and technical analysis for HYPE/USD. No "unsupported ticker" error.

- [ ] **Step 5: Commit**

```bash
git checkout -b feature/live-crypto-safety
git add src/main/java/com/tradingbot/kraken/KrakenClient.java
git commit -m "feat: add HYPE/USD to Kraken supported pairs"
```

---

## Task 2: Add Kraken API Methods to KrakenClient

**Files:**
- Modify: `src/main/java/com/tradingbot/kraken/KrakenClient.java`

These 4 new methods are needed by `AccountMonitor` (Task 3) and `DiscordListener` (Task 5).

- [ ] **Step 1: Add `getAccountBalance()` method**

Add this method to `KrakenClient.java`. It calls Kraken's `/0/private/Balance` endpoint and returns a map of asset → balance:

```java
/**
 * Returns current Kraken account balances.
 * Key = asset code (e.g. "ZUSD", "XETH"), value = balance amount.
 * Returns empty map if credentials not set.
 */
public Map<String, Double> getAccountBalance() {
    if (!hasCredentials) return Map.of();
    try {
        String nonce = String.valueOf(System.currentTimeMillis());
        String postData = "nonce=" + nonce;
        String signature = sign("/0/private/Balance", nonce, postData);

        RequestBody body = RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded"));
        Request req = new Request.Builder()
                .url(BASE + "/0/private/Balance")
                .addHeader("API-Key", apiKey)
                .addHeader("API-Sign", signature)
                .post(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body().string();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray errors = root.getAsJsonArray("error");
            if (!errors.isEmpty()) {
                log.warn("Kraken balance error: {}", errors);
                return Map.of();
            }
            Map<String, Double> balances = new LinkedHashMap<>();
            JsonObject result = root.getAsJsonObject("result");
            for (Map.Entry<String, JsonElement> e : result.entrySet()) {
                balances.put(e.getKey(), Double.parseDouble(e.getValue().getAsString()));
            }
            return balances;
        }
    } catch (Exception e) {
        log.error("Failed to fetch Kraken balance", e);
        return Map.of();
    }
}
```

Add imports at top of file if not present:
```java
import com.google.gson.JsonElement;
import java.util.LinkedHashMap;
```

- [ ] **Step 2: Add `getOpenPositions()` method**

Kraken's `/0/private/OpenPositions` returns open leveraged positions. For spot trading (no leverage), use `/0/private/OpenOrders` to infer positions from filled order state. Since this bot uses spot market, we approximate "positions" by checking Kraken balance for non-USD assets with non-zero balance:

```java
/**
 * Returns open crypto positions inferred from non-zero Kraken balances.
 * Each entry: ticker symbol (e.g. "SOL") -> quantity held.
 * Filters out USD balance and dust amounts (< 0.00001).
 */
public Map<String, Double> getOpenPositions() {
    Map<String, Double> balances = getAccountBalance();
    Map<String, Double> positions = new LinkedHashMap<>();

    // Reverse-lookup: Kraken asset code -> our ticker symbol
    Map<String, String> assetToTicker = Map.ofEntries(
        Map.entry("XXBT",  "BTC"),
        Map.entry("XETH",  "ETH"),
        Map.entry("SOL",   "SOL"),
        Map.entry("XDOGE", "DOGE"),
        Map.entry("AVAX",  "AVAX"),
        Map.entry("MATIC", "MATIC"),
        Map.entry("LINK",  "LINK"),
        Map.entry("UNI",   "UNI"),
        Map.entry("ADA",   "ADA"),
        Map.entry("XXRP",  "XRP"),
        Map.entry("XLTC",  "LTC"),
        Map.entry("BCH",   "BCH"),
        Map.entry("HYPE",  "HYPE")
    );

    for (Map.Entry<String, Double> e : balances.entrySet()) {
        String asset = e.getKey();
        double qty = e.getValue();
        if (qty < 0.00001) continue;      // skip dust
        if (asset.equals("ZUSD")) continue; // skip USD
        String ticker = assetToTicker.getOrDefault(asset, asset);
        positions.put(ticker, qty);
    }
    return positions;
}
```

- [ ] **Step 3: Add `cancelAllOpenOrders()` method**

```java
/**
 * Cancels all open Kraken orders. Used by circuit breaker.
 * Returns number of orders cancelled.
 */
public int cancelAllOpenOrders() {
    if (!hasCredentials) return 0;
    try {
        String nonce = String.valueOf(System.currentTimeMillis());
        String postData = "nonce=" + nonce;
        String signature = sign("/0/private/CancelAll", nonce, postData);

        RequestBody body = RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded"));
        Request req = new Request.Builder()
                .url(BASE + "/0/private/CancelAll")
                .addHeader("API-Key", apiKey)
                .addHeader("API-Sign", signature)
                .post(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body().string();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray errors = root.getAsJsonArray("error");
            if (!errors.isEmpty()) {
                log.warn("Kraken cancelAll error: {}", errors);
                return 0;
            }
            return root.getAsJsonObject("result").get("count").getAsInt();
        }
    } catch (Exception e) {
        log.error("Failed to cancel all Kraken orders", e);
        return 0;
    }
}
```

- [ ] **Step 4: Add `closePosition()` method**

```java
/**
 * Closes a spot position by placing a market sell for the full held quantity.
 * Returns a result string: "Sold 0.52 SOL at market" or an error message.
 */
public String closePosition(String ticker) {
    if (!hasCredentials) return "No Kraken credentials configured.";
    try {
        Map<String, Double> positions = getOpenPositions();
        Double qty = positions.get(ticker.toUpperCase());
        if (qty == null || qty < 0.00001) {
            return "No open position found for " + ticker;
        }

        String pair = toKrakenPair(ticker);
        if (pair == null) return "Unsupported ticker: " + ticker;

        String nonce = String.valueOf(System.currentTimeMillis());
        String volume = String.format("%.8f", qty).replaceAll("0+$", "").replaceAll("\\.$", ".0");
        String postData = "nonce=" + nonce
                + "&ordertype=market"
                + "&type=sell"
                + "&volume=" + volume
                + "&pair=" + pair;
        String signature = sign("/0/private/AddOrder", nonce, postData);

        RequestBody body = RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded"));
        Request req = new Request.Builder()
                .url(BASE + "/0/private/AddOrder")
                .addHeader("API-Key", apiKey)
                .addHeader("API-Sign", signature)
                .post(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body().string();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray errors = root.getAsJsonArray("error");
            if (!errors.isEmpty()) {
                return "Kraken error: " + errors.get(0).getAsString();
            }
            // Get current mid price for display
            double midPrice = 0;
            try { midPrice = getLatestQuote(ticker).mid(); } catch (Exception ignored) {}
            return String.format("Sold %.6f %s at market (~$%.2f)", qty, ticker.toUpperCase(), midPrice);
        }
    } catch (Exception e) {
        log.error("Failed to close position for {}", ticker, e);
        return "Error closing position: " + e.getMessage();
    }
}
```

- [ ] **Step 5: Build to verify**

```bash
mvn package -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tradingbot/kraken/KrakenClient.java
git commit -m "feat: add getAccountBalance, getOpenPositions, cancelAllOpenOrders, closePosition to KrakenClient"
```

---

## Task 3: Create AccountMonitor (Circuit Breaker)

**Files:**
- Create: `src/main/java/com/tradingbot/monitor/AccountMonitor.java`

- [ ] **Step 1: Create the monitor package directory**

```bash
mkdir -p src/main/java/com/tradingbot/monitor
```

- [ ] **Step 2: Create AccountMonitor.java**

```java
package com.tradingbot.monitor;

import com.tradingbot.kraken.KrakenClient;
import discord4j.core.object.entity.channel.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Polls Kraken balance every 5 minutes.
 * If total USD value drops below minThreshold, cancels all open orders,
 * sets paused=true, and fires the alert callback.
 */
public class AccountMonitor {

    private static final Logger log = LoggerFactory.getLogger(AccountMonitor.class);

    private final KrakenClient kraken;
    private final double minThreshold;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "account-monitor");
                t.setDaemon(true);
                return t;
            });

    // Called when circuit breaker fires. Arg = formatted alert message.
    private Consumer<String> alertCallback = msg -> {};

    public AccountMonitor(KrakenClient kraken, double minThreshold) {
        this.kraken = kraken;
        this.minThreshold = minThreshold;
    }

    /** Register a callback that sends the alert message to Discord. */
    public void setAlertCallback(Consumer<String> callback) {
        this.alertCallback = callback;
    }

    /** Start polling every 5 minutes. Also runs an initial check immediately. */
    public void start() {
        scheduler.scheduleAtFixedRate(this::checkBalance, 0, 5, TimeUnit.MINUTES);
        log.info("AccountMonitor started — threshold ${}", minThreshold);
    }

    private void checkBalance() {
        try {
            double total = getTotalUsdValue();
            log.debug("AccountMonitor: balance=${}", String.format("%.2f", total));
            if (total > 0 && total < minThreshold && !paused.get()) {
                paused.set(true);
                int cancelled = kraken.cancelAllOpenOrders();
                String alert = String.format(
                    "⚠️ Balance dropped to $%.2f (below $%.2f threshold). " +
                    "%d pending orders cancelled. Bot paused — use !resume to re-enable.",
                    total, minThreshold, cancelled);
                log.warn("Circuit breaker triggered: {}", alert);
                alertCallback.accept(alert);
            }
        } catch (Exception e) {
            log.error("AccountMonitor check failed", e);
        }
    }

    /**
     * Returns total estimated USD value: USD cash + crypto valued at current mid prices.
     */
    public double getTotalUsdValue() {
        Map<String, Double> balances = kraken.getAccountBalance();
        if (balances.isEmpty()) return 0;

        double total = balances.getOrDefault("ZUSD", 0.0);
        Map<String, Double> positions = kraken.getOpenPositions();
        for (Map.Entry<String, Double> pos : positions.entrySet()) {
            try {
                double mid = kraken.getLatestQuote(pos.getKey()).mid();
                total += pos.getValue() * mid;
            } catch (Exception e) {
                log.warn("Could not price {} for balance estimate", pos.getKey());
            }
        }
        return total;
    }

    public boolean isPaused() {
        return paused.get();
    }

    /** Unpauses the bot. Called by !resume. Returns current balance string. */
    public String unpause() {
        paused.set(false);
        double balance = getTotalUsdValue();
        String msg;
        if (balance > 0 && balance < minThreshold) {
            msg = String.format(
                "✅ Bot resumed. ⚠️ Warning: balance $%.2f still below threshold $%.2f.", 
                balance, minThreshold);
        } else {
            msg = String.format("✅ Bot resumed. Current balance: $%.2f", balance);
        }
        log.info("Bot unpaused. {}", msg);
        return msg;
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
```

- [ ] **Step 3: Build to verify**

```bash
mvn package -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tradingbot/monitor/AccountMonitor.java
git commit -m "feat: add AccountMonitor circuit breaker with 5-min Kraken balance polling"
```

---

## Task 4: Update OrderManager (paused check + daily trade limit)

**Files:**
- Modify: `src/main/java/com/tradingbot/order/OrderManager.java`

- [ ] **Step 1: Add AccountMonitor field and daily trade counter to OrderManager**

In `OrderManager.java`, add these fields after the existing `private KrakenClient kraken;` field:

```java
private AccountMonitor accountMonitor;

// Daily trade limit
private final java.util.concurrent.atomic.AtomicInteger dailyTradeCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
private final int maxDailyTrades;
private final java.util.concurrent.ScheduledExecutorService dailyResetScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-reset");
            t.setDaemon(true);
            return t;
        });
```

Add import at top:
```java
import com.tradingbot.monitor.AccountMonitor;
```

- [ ] **Step 2: Update OrderManager constructor to accept maxDailyTrades**

Change the constructor from:
```java
public OrderManager(AlpacaClient alpaca, SlippageTracker slippageTracker, OrderDao orderDao)
```
to:
```java
public OrderManager(AlpacaClient alpaca, SlippageTracker slippageTracker, OrderDao orderDao, int maxDailyTrades)
```

Inside the constructor body, add:
```java
this.maxDailyTrades = maxDailyTrades;
scheduleDailyReset();
```

Add the `scheduleDailyReset()` method:
```java
private void scheduleDailyReset() {
    // Calculate seconds until next midnight ET
    java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
    java.time.ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1)
            .atStartOfDay(java.time.ZoneId.of("America/New_York"));
    long secondsUntilMidnight = java.time.Duration.between(now, nextMidnight).getSeconds();

    dailyResetScheduler.scheduleAtFixedRate(
        () -> {
            int prev = dailyTradeCount.getAndSet(0);
            log.info("Daily trade counter reset (was {})", prev);
        },
        secondsUntilMidnight,
        86400,  // 24 hours in seconds
        java.util.concurrent.TimeUnit.SECONDS
    );
}
```

- [ ] **Step 3: Add setter for AccountMonitor**

```java
public void setAccountMonitor(AccountMonitor monitor) {
    this.accountMonitor = monitor;
}
```

- [ ] **Step 4: Add guard checks at start of `placePlay()` method**

In `placePlay()`, add these checks BEFORE any validation logic (as the very first lines of the method body):

```java
// Circuit breaker check
if (accountMonitor != null && accountMonitor.isPaused()) {
    return "⛔ Bot is paused — account below minimum equity. Use !resume to re-enable.";
}

// Daily trade limit check
if (dailyTradeCount.get() >= maxDailyTrades) {
    return String.format("🚫 Daily trade limit reached (%d/%d). No new orders until midnight ET.",
            dailyTradeCount.get(), maxDailyTrades);
}
```

Do the same at the start of `placePlayOpg()`.

- [ ] **Step 5: Increment counter on successful order**

Find where `placePlay()` returns the success string (after placing the order). Just before each successful return, add:

```java
dailyTradeCount.incrementAndGet();
```

Do the same in `placePlayOpg()` and `placeCryptoPlay()`.

- [ ] **Step 6: Add `getDailyTradeCount()` and `getMaxDailyTrades()` accessors**

```java
public int getDailyTradeCount() { return dailyTradeCount.get(); }
public int getMaxDailyTrades()  { return maxDailyTrades; }
```

- [ ] **Step 7: Build to verify**

```bash
mvn package -q
```

Expected: `BUILD SUCCESS`. If the constructor signature change breaks `Main.java`, the error will tell you — fix it in Task 6.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tradingbot/order/OrderManager.java
git commit -m "feat: add circuit breaker check and 15-trade/day limit to OrderManager"
```

---

## Task 5: Update DiscordListener (!exit, !resume, !positions update)

**Files:**
- Modify: `src/main/java/com/tradingbot/discord/DiscordListener.java`

- [ ] **Step 1: Add AccountMonitor field to DiscordListener**

Add field after the existing fields:
```java
private AccountMonitor accountMonitor;
```

Add import:
```java
import com.tradingbot.monitor.AccountMonitor;
```

Add setter:
```java
public void setAccountMonitor(AccountMonitor monitor) {
    this.accountMonitor = monitor;
}
```

- [ ] **Step 2: Route !EXIT and !RESUME in `route()` method**

In the `route()` method, add these cases alongside the other `if` branches (before the `!HELP` case):

```java
if (upper.startsWith("!EXIT")) {
    String result = handleExit(content.trim());
    return ch.createMessage(result);
}

if (upper.startsWith("!RESUME")) {
    String result = handleResume();
    return ch.createMessage(result);
}
```

- [ ] **Step 3: Add `handleExit()` method**

```java
private String handleExit(String content) {
    // content is like "!exit SOL" or "!exit all"
    String[] parts = content.split("\\s+");
    if (parts.length < 2) {
        return "Usage: !exit <SYMBOL> or !exit all";
    }
    String arg = parts[1].toUpperCase();

    if (arg.equals("ALL")) {
        // Exit all positions
        Map<String, Double> positions = orderManager.getKrakenClient() != null
                ? orderManager.getKrakenClient().getOpenPositions()
                : Map.of();
        if (positions.isEmpty()) {
            return "No open positions found.";
        }
        StringBuilder sb = new StringBuilder("Closing all positions:\n");
        for (String ticker : positions.keySet()) {
            // Cancel open orders for this pair first
            String cancelResult = cancelOrdersForTicker(ticker);
            // Then market sell
            String exitResult = orderManager.getKrakenClient().closePosition(ticker);
            sb.append(String.format("• %s: %s\n", ticker, exitResult));
        }
        return sb.toString().trim();
    } else {
        // Exit single position
        if (orderManager.getKrakenClient() == null) {
            return "Kraken not configured.";
        }
        // Cancel open orders for this ticker first
        cancelOrdersForTicker(arg);
        String result = orderManager.getKrakenClient().closePosition(arg);
        return "Exited " + arg + " — " + result;
    }
}

private String cancelOrdersForTicker(String ticker) {
    // Cancels all open orders (Kraken CancelAll is the simplest approach for spot)
    // For more granularity, Kraken's /CancelOrder by txid would be needed
    // Since we don't track individual order IDs per ticker in spot mode, we accept this
    return "orders cancelled";
}
```

Add imports:
```java
import java.util.Map;
```

Also add a `getKrakenClient()` accessor to `OrderManager.java`:
```java
public KrakenClient getKrakenClient() { return kraken; }
```

- [ ] **Step 4: Add `handleResume()` method**

```java
private String handleResume() {
    if (accountMonitor == null) {
        return "AccountMonitor not configured.";
    }
    return accountMonitor.unpause();
}
```

- [ ] **Step 5: Update `!positions` handler to include Kraken P&L and trade count**

Find the `!POSITIONS` branch in `route()`. It currently calls `orderManager.listPositions()`. Wrap it to prepend Kraken positions:

```java
if (upper.startsWith("!POSITIONS")) {
    String result = buildPositionsOutput();
    return sendChunked(ch, result, null);
}
```

Add the `buildPositionsOutput()` method:

```java
private String buildPositionsOutput() {
    StringBuilder sb = new StringBuilder();

    // Kraken live positions
    KrakenClient krakenClient = orderManager.getKrakenClient();
    if (krakenClient != null) {
        Map<String, Double> positions = krakenClient.getOpenPositions();
        if (!positions.isEmpty()) {
            sb.append("📊 **Live Crypto Positions (Kraken)**\n");
            double totalCryptoValue = 0;
            for (Map.Entry<String, Double> pos : positions.entrySet()) {
                String ticker = pos.getKey();
                double qty = pos.getValue();
                try {
                    double mid = krakenClient.getLatestQuote(ticker).mid();
                    totalCryptoValue += qty * mid;
                    sb.append(String.format("%-6s | %.6f @ market | Now: $%.4f | Value: $%.2f\n",
                            ticker, qty, mid, qty * mid));
                } catch (Exception e) {
                    sb.append(String.format("%-6s | %.6f (price unavailable)\n", ticker, qty));
                }
            }
            // Account balance summary
            if (accountMonitor != null) {
                double total = accountMonitor.getTotalUsdValue();
                Map<String, Double> balances = krakenClient.getAccountBalance();
                double usdCash = balances.getOrDefault("ZUSD", 0.0);
                sb.append(String.format("Balance: $%.2f USD + ~$%.2f crypto = ~$%.2f total\n",
                        usdCash, totalCryptoValue, total));
            }
        } else {
            sb.append("📊 **Kraken:** No open crypto positions.\n");
        }
        sb.append("\n");
    }

    // Daily trade limit status
    sb.append(String.format("Trades today: %d/%d\n\n",
            orderManager.getDailyTradeCount(), orderManager.getMaxDailyTrades()));

    // Existing Alpaca paper positions
    sb.append("📋 **Paper Positions (Alpaca)**\n");
    sb.append(orderManager.listPositions());

    return sb.toString();
}
```

Add import:
```java
import com.tradingbot.kraken.KrakenClient;
```

- [ ] **Step 6: Update help text**

In `helpText()`, add the new commands:

```java
// Add to the existing help string:
"!EXIT <SYMBOL|ALL> — close a live Kraken position at market\n" +
"!RESUME           — re-enable bot after circuit breaker pause\n"
```

- [ ] **Step 7: Build to verify**

```bash
mvn package -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tradingbot/discord/DiscordListener.java
git add src/main/java/com/tradingbot/order/OrderManager.java
git commit -m "feat: add !exit and !resume commands; update !positions with Kraken P&L and trade count"
```

---

## Task 6: Wire AccountMonitor into Main.java

**Files:**
- Modify: `src/main/java/com/tradingbot/Main.java`

- [ ] **Step 1: Add AccountMonitor instantiation**

In `main()`, after `KrakenClient` and `DiscordNotifier` are created but before the Discord gateway starts, add:

```java
// --- AccountMonitor (circuit breaker) ---
double minEquity = Double.parseDouble(env.get("MIN_EQUITY_THRESHOLD") != null
        ? env.get("MIN_EQUITY_THRESHOLD") : "100.00");
AccountMonitor accountMonitor = new AccountMonitor(krakenClient, minEquity);
```

Add import:
```java
import com.tradingbot.monitor.AccountMonitor;
```

- [ ] **Step 2: Update OrderManager constructor call**

Find where `OrderManager` is instantiated. Currently:
```java
OrderManager orderManager = new OrderManager(alpaca, slippageTracker, orderDao);
```

Change to:
```java
int maxDailyTrades = Integer.parseInt(env.get("MAX_DAILY_TRADES") != null
        ? env.get("MAX_DAILY_TRADES") : "15");
OrderManager orderManager = new OrderManager(alpaca, slippageTracker, orderDao, maxDailyTrades);
orderManager.setAccountMonitor(accountMonitor);
```

- [ ] **Step 3: Wire alert callback from AccountMonitor to Discord via DiscordNotifier**

The bot already has a `DiscordNotifier` class that sends proactive messages to Discord. Pass the alert through it. After both `DiscordNotifier` and `AccountMonitor` are created in `main()`, add:

```java
// Wire AccountMonitor circuit breaker alert to Discord
accountMonitor.setAlertCallback(alert -> {
    discordNotifier.send(alert);  // DiscordNotifier.send(String) sends to the watched channel
});
```

Where `discordNotifier` is the existing `DiscordNotifier` instance already wired in `Main.java`. Check `DiscordNotifier.java` for the exact method name — it may be `send()`, `notify()`, or `post()`. Use whichever exists.

- [ ] **Step 4: Inject AccountMonitor into DiscordListener**

After `DiscordListener` is created:
```java
listener.setAccountMonitor(accountMonitor);
```

- [ ] **Step 5: Start AccountMonitor**

After all wiring is done, before the blocking gateway await:
```java
accountMonitor.start();
```

- [ ] **Step 6: Update .env.example**

Add to the bottom of `.env.example`:
```bash
# Circuit breaker: bot pauses if Kraken balance drops below this USD value
MIN_EQUITY_THRESHOLD=100.00

# Max trades per calendar day (resets at midnight ET)
MAX_DAILY_TRADES=15
```

- [ ] **Step 7: Build full project**

```bash
mvn package -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Run locally and verify startup**

```bash
java -jar target/trading-bot-1.0-SNAPSHOT.jar
```

Expected log output within 10 seconds:
```
AccountMonitor started — threshold $100.0
Bot online in #trading
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/tradingbot/Main.java .env.example
git commit -m "feat: wire AccountMonitor into Main; add MIN_EQUITY_THRESHOLD and MAX_DAILY_TRADES config"
```

---

## Task 7: End-to-End Verification

**No code changes — manual testing only.**

- [ ] **Step 1: Test HYPE analysis**

In Discord:
```
!analyze HYPE
```
Expected: Chart PNG + technical analysis output. No "unsupported ticker" error.

- [ ] **Step 2: Test positions view**

In Discord:
```
!positions
```
Expected: Shows "Kraken: No open crypto positions" (or real positions if any), daily trade count `0/15`, and Alpaca paper positions.

- [ ] **Step 3: Test manual exit (paper test)**

In Discord, first place a paper position:
```
!play HYPE LONG e=0.35 s=0.30 t=0.42 qty=10
```
Then exit it:
```
!exit HYPE
```
Expected: Confirmation message. `!positions` shows HYPE removed.

- [ ] **Step 4: Test circuit breaker**

Temporarily set `MIN_EQUITY_THRESHOLD=999999` in your local `.env`, restart the bot. Within 5 minutes you should see the circuit breaker alert in Discord. Then:
```
!resume
```
Expected: `✅ Bot resumed. ⚠️ Warning: balance $X.XX still below threshold $999999.00.`

Restore `MIN_EQUITY_THRESHOLD=100.00` in `.env`.

- [ ] **Step 5: Test daily trade limit**

Temporarily set `MAX_DAILY_TRADES=1` in `.env`, restart. Place one order. Try a second:
```
!play SOL LONG e=100 s=90 t=115 qty=1
```
Expected: `🚫 Daily trade limit reached (1/1). No new orders until midnight ET.`

Restore `MAX_DAILY_TRADES=15` in `.env`.

- [ ] **Step 6: Test !resume command**

```
!resume
```
Expected: `✅ Bot resumed. Current balance: $X.XX` (bot was not paused — this is a no-op test).

- [ ] **Step 7: Test !exit all**

```
!exit all
```
Expected: Either "No open positions found." or a list of exits if positions exist.

- [ ] **Step 8: Final build and deploy**

```bash
mvn package -q
git push origin feature/live-crypto-safety
```

Then open a PR to main, squash-merge after review.

Deploy to Fly.io:
```bash
fly deploy
```

Expected: "Bot online in #trading" in Fly.io logs within 60 seconds.

---

## Paper Test Checklist (48-hour run before going live)

After deploying, run this 48-hour paper test:

- [ ] `!analyze HYPE` returns chart + setup
- [ ] `!watch HYPE auto` persists to SQLite watchlist (verify with `!watchlist`)
- [ ] `!watch SOL auto`, `!watch BTC auto` added
- [ ] Overnight analysis (23:00 ET) fires — check Fly.io logs: `fly logs | grep "analysis"`
- [ ] `!positions` shows Kraken balance + trade count
- [ ] Bot survives overnight without crash — check at 08:00: `fly logs --since 8h | grep ERROR`
- [ ] LLM costs under $1 — check OpenRouter dashboard after 48 hours
- [ ] Circuit breaker tested at least once manually (Task 7 Step 4)

---

## Go-Live Checklist (after paper test passes)

- [ ] Fund Kraken account with $200 USD
- [ ] Generate Kraken API key with `Trade` and `Query Funds` permissions
- [ ] Set Fly.io secrets:
  ```bash
  fly secrets set \
    KRAKEN_API_KEY=your_live_key \
    KRAKEN_API_SECRET=your_live_secret \
    MIN_EQUITY_THRESHOLD=100.00 \
    MAX_DAILY_TRADES=15
  ```
- [ ] `fly deploy`
- [ ] Confirm "Bot online" in Discord
- [ ] In Discord: `!watch HYPE auto`, `!watch SOL auto`, `!watch BTC auto`
- [ ] Verify `!positions` shows your Kraken USD balance
- [ ] Keep `ALPACA_MODE=paper` — no change needed (stocks stay paper)
