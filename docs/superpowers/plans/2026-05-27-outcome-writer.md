# Phase-4 Outcome Writer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a placed bracket trade closes (stop or take-profit), record its realized outcome to the `position_outcomes` table so the dormant `setup_performance` view returns real win-rates that get fed into the LLM analysis prompt — closing the feedback loop for both Alpaca and Kraken.

**Architecture:** Add a `conviction` column to `orders` and `position_outcomes` (V3 migration) and rewrite `setup_performance` to read outcomes directly (no `setups`/`analyses` wiring). Thread conviction from the `TradeIdea` through order placement so it persists on the order row. A new `OutcomeTracker` polls open orders every 60s: for Alpaca it reads the nested bracket legs to see which leg filled; for Kraken it uses a new signed `QueryOrders` call for the take-profit leg and infers a stop-out from the position disappearing. Exit price is taken from the stored stop/target level (per decision), PnL and WIN/LOSS computed from that, and the order marked closed so it isn't recorded twice.

**Tech Stack:** Java 21, JDBI 3 + SQLite (Flyway migrations), OkHttp, Gson. No test framework (verified by running — matches the repo's current no-test state).

**Verification note:** This project has zero unit tests and no JUnit in `pom.xml`. Per decision, each task is verified by **compiling** (`mvn package -q -DskipTests`) and the final feature is verified by **running a real crypto trade** and inspecting the SQLite DB — not by automated tests.

---

### Task 1: V3 migration — conviction columns + rewritten view

**Files:**
- Create: `src/main/resources/db/migration/V3__outcome_conviction.sql`

- [ ] **Step 1: Write the migration**

```sql
-- M3 (Phase 4): record conviction on orders and outcomes so the feedback-loop view
-- can group realized win-rates by conviction without the dormant setups/analyses tables.

ALTER TABLE orders ADD COLUMN conviction TEXT;             -- HIGH | MEDIUM | LOW | MANUAL | NULL
ALTER TABLE position_outcomes ADD COLUMN conviction TEXT;  -- copied from the order at close time

-- Rewrite setup_performance to read straight from position_outcomes (the old definition
-- joined setups/orders on orders.setup_id, which is never populated → always empty).
DROP VIEW IF EXISTS setup_performance;
CREATE VIEW setup_performance AS
SELECT
    direction,
    conviction,
    COUNT(*)                                              AS total,
    SUM(CASE WHEN outcome = 'WIN' THEN 1 ELSE 0 END)      AS wins,
    ROUND(100.0 * SUM(CASE WHEN outcome = 'WIN' THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_pct,
    ROUND(AVG(pnl), 2)                                    AS avg_pnl
FROM position_outcomes
WHERE conviction IS NOT NULL
GROUP BY direction, conviction;
```

- [ ] **Step 2: Verify it compiles into the build**

Run: `mvn package -q -DskipTests`
Expected: `EXIT=0`, jar produced. (Flyway runs the migration at bot startup, not at build time — startup is exercised in Task 9.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V3__outcome_conviction.sql
git commit -m "feat(db): V3 adds conviction columns and rebuilds setup_performance view"
```

---

### Task 2: PerformanceDao records conviction

**Files:**
- Modify: `src/main/java/com/tradingbot/db/PerformanceDao.java:26-43` (the `recordOutcome` method)

- [ ] **Step 1: Replace `recordOutcome` to accept and store conviction**

```java
    /** Records the realized outcome of a closed position. outcome: WIN | LOSS | BREAKEVEN. */
    public void recordOutcome(Long orderId, String ticker, String direction,
                              double entryPrice, double exitPrice, int qty,
                              double pnl, String outcome, String conviction) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO position_outcomes(order_id, ticker, direction, entry_price,
                                              exit_price, qty, pnl, outcome, conviction)
                VALUES(:orderId, :ticker, :dir, :entry, :exit, :qty, :pnl, :outcome, :conviction)
                """)
            .bind("orderId", orderId)
            .bind("ticker",  ticker.toUpperCase())
            .bind("dir",     direction.toUpperCase())
            .bind("entry",   entryPrice)
            .bind("exit",    exitPrice)
            .bind("qty",     qty)
            .bind("pnl",     pnl)
            .bind("outcome", outcome.toUpperCase())
            .bind("conviction", conviction == null ? null : conviction.toUpperCase())
            .execute());
    }
```

> The `setupPerformance()` query and `performanceContext()` are unchanged — the view still exposes `direction, conviction, total, wins, win_pct, avg_pnl`, so `SetupStat` still maps.

- [ ] **Step 2: Verify compile**

Run: `mvn package -q -DskipTests`
Expected: FAIL — `PerformanceDao` has no other callers yet, but the old 8-arg `recordOutcome` is now gone; if anything referenced it, fix here. (Nothing does today, so expect `EXIT=0`.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tradingbot/db/PerformanceDao.java
git commit -m "feat(db): PerformanceDao.recordOutcome stores conviction"
```

---

### Task 3: OrderDao stores conviction + exposes open orders for outcome tracking

**Files:**
- Modify: `src/main/java/com/tradingbot/db/OrderDao.java`

- [ ] **Step 1: Add a `conviction` param to `recordAlpacaOrder` (replace lines 22-44)**

```java
    /** Records an Alpaca bracket order. tif: "day", "opg", or "gtc". conviction may be null. */
    public long recordAlpacaOrder(String ticker, String direction, double entry,
                                   double stop, double target, int qty,
                                   String orderId, String tif, String conviction) {
        return jdbi.withHandle(h ->
            h.createUpdate("""
                INSERT INTO orders(ticker, broker, order_id, direction,
                                   entry_price, stop_loss, target, qty, tif, conviction)
                VALUES(:ticker, 'ALPACA', :orderId, :dir,
                       :entry, :stop, :target, :qty, :tif, :conviction)
                """)
             .bind("ticker",  ticker.toUpperCase())
             .bind("orderId", orderId)
             .bind("dir",     direction.toUpperCase())
             .bind("entry",   entry)
             .bind("stop",    stop)
             .bind("target",  target)
             .bind("qty",     qty)
             .bind("tif",     tif)
             .bind("conviction", conviction == null ? null : conviction.toUpperCase())
             .executeAndReturnGeneratedKeys("id")
             .mapTo(Long.class)
             .one());
    }
```

- [ ] **Step 2: Add a `conviction` param to `recordKrakenOrder` (replace lines 51-72)**

```java
    /**
     * Records a Kraken two-order bracket.
     * entryTxid: entry+stop-loss conditional order txid.
     * tpTxid: separate take-profit limit txid. conviction may be null.
     */
    public long recordKrakenOrder(String ticker, String direction, double entry,
                                   double stop, double target, int qty,
                                   String entryTxid, String tpTxid, String conviction) {
        return jdbi.withHandle(h ->
            h.createUpdate("""
                INSERT INTO orders(ticker, broker, order_id, tp_order_id, direction,
                                   entry_price, stop_loss, target, qty, tif, conviction)
                VALUES(:ticker, 'KRAKEN', :entryTxid, :tpTxid, :dir,
                       :entry, :stop, :target, :qty, 'gtc', :conviction)
                """)
             .bind("ticker",    ticker.toUpperCase())
             .bind("entryTxid", entryTxid)
             .bind("tpTxid",    tpTxid)
             .bind("dir",       direction.toUpperCase())
             .bind("entry",     entry)
             .bind("stop",      stop)
             .bind("target",    target)
             .bind("qty",       qty)
             .bind("conviction", conviction == null ? null : conviction.toUpperCase())
             .executeAndReturnGeneratedKeys("id")
             .mapTo(Long.class)
             .one());
    }
```

- [ ] **Step 3: Add an `OpenOrder` record, a query for outcome candidates, and a `markClosed` method (insert after `recordKrakenOrder`, before `updateStatus`)**

```java
    /** A placed order still eligible for outcome recording (entry possibly open or filled). */
    public record OpenOrder(long id, String broker, String orderId, String tpOrderId,
                            String ticker, String direction, double entry, double stop,
                            double target, int qty, String conviction, String status) {}

    /**
     * Orders that have NOT yet had an outcome recorded. Excludes terminal states.
     * 'closed' is the marker set once an outcome row is written.
     */
    public java.util.List<OpenOrder> listOpenForOutcome() {
        return jdbi.withHandle(h ->
            h.createQuery("""
                SELECT id, broker, order_id, tp_order_id, ticker, direction,
                       entry_price, stop_loss, target, qty, conviction, status
                FROM orders
                WHERE status NOT IN ('closed', 'cancelled')
                """)
             .map((rs, ctx) -> new OpenOrder(
                     rs.getLong("id"),
                     rs.getString("broker"),
                     rs.getString("order_id"),
                     rs.getString("tp_order_id"),
                     rs.getString("ticker"),
                     rs.getString("direction"),
                     rs.getDouble("entry_price"),
                     rs.getDouble("stop_loss"),
                     rs.getDouble("target"),
                     rs.getInt("qty"),
                     rs.getString("conviction"),
                     rs.getString("status")))
             .list());
    }

    /** Marks an order row as closed (outcome recorded) so it is not double-counted. */
    public void markClosed(long id) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE orders SET status = 'closed' WHERE id = :id")
            .bind("id", id)
            .execute());
    }
```

- [ ] **Step 4: Verify compile (expect failure pointing at OrderManager)**

Run: `mvn package -q -DskipTests`
Expected: FAIL — `OrderManager` still calls the old 8-arg `recordAlpacaOrder` / 8-arg `recordKrakenOrder`. Fixed in Task 4.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/db/OrderDao.java
git commit -m "feat(db): persist conviction on orders; add open-order query + markClosed"
```

---

### Task 4: Thread conviction through OrderManager and its callers

**Files:**
- Modify: `src/main/java/com/tradingbot/order/OrderManager.java`
- Modify: `src/main/java/com/tradingbot/discord/DiscordListener.java`
- Modify: `src/main/java/com/tradingbot/scheduler/WatchlistScheduler.java`

- [ ] **Step 1: `OrderManager.placePlay` — add `conviction` param and pass it to the recorder**

Change the signature (currently line 65-66) and the recorder call (line 108):

```java
    public String placePlay(String ticker, String direction, double entry,
                             double stop, double target, int qty, String conviction) throws Exception {
```

Replace the crypto-route line (currently `return placeCryptoPlay(ticker, direction, entry, stop, target, qty, rr);`) with:

```java
        if (AlpacaClient.isCrypto(ticker)) {
            return placeCryptoPlay(ticker, direction, entry, stop, target, qty, rr, conviction);
        }
```

Replace the recorder line (currently line 108) with:

```java
        orderDao.recordAlpacaOrder(ticker, direction, entry, stop, target, qty, orderId, "day", conviction);
```

- [ ] **Step 2: `OrderManager.placePlayOpg` — same treatment**

Change its signature (line 132-133) to add `String conviction`, change its crypto route to call `placeCryptoPlay(..., qty, rr, conviction)`, and change its recorder line (line 173) to:

```java
        orderDao.recordAlpacaOrder(ticker, direction, entry, stop, target, qty, orderId, "opg", conviction);
```

- [ ] **Step 3: `OrderManager.placeCryptoPlay` — add `conviction` param and pass to recorder**

Change signature (line 192-193) to:

```java
    private String placeCryptoPlay(String ticker, String direction, double entry,
                                    double stop, double target, int qty, double rr,
                                    String conviction) throws Exception {
```

Change its recorder call (line 204) to:

```java
        orderDao.recordKrakenOrder(ticker, direction, entry, stop, target, qty, entryTxid, tpTxid, conviction);
```

- [ ] **Step 4: `DiscordListener.handlePick` — pass the chosen idea's conviction**

In `handlePick` (the `return orderManager.placePlay(...)` call near line 377-383), change to:

```java
        return orderManager.placePlay(
                chosen.getTicker(),
                chosen.getDirection().name(),
                chosen.getEntry(),
                chosen.getStopLoss(),
                chosen.getTarget(),
                qty,
                chosen.getConviction().name());
```

- [ ] **Step 5: `DiscordListener.parseAndPlaceOrder` — manual `!play` has no conviction**

Change its final `return orderManager.placePlay(ticker, direction, entry, stop, target, qty);` to:

```java
        return orderManager.placePlay(ticker, direction, entry, stop, target, qty, "MANUAL");
```

- [ ] **Step 6: `WatchlistScheduler.autoPlace` — pass the idea's conviction**

In `autoPlace` the `orderManager.placePlayOpg(...)` call (near line 189-196) becomes:

```java
                int qty = idea.getSuggestedQty() > 0 ? idea.getSuggestedQty() : defaultQty;
                String confirmation = orderManager.placePlayOpg(
                        idea.getTicker(),
                        idea.getDirection().name(),
                        idea.getEntry(),
                        idea.getStopLoss(),
                        idea.getTarget(),
                        qty,
                        idea.getConviction().name());
```

- [ ] **Step 7: Verify compile**

Run: `mvn package -q -DskipTests`
Expected: `EXIT=0`. (All `placePlay`/`placePlayOpg` call sites now pass conviction.)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tradingbot/order/OrderManager.java src/main/java/com/tradingbot/discord/DiscordListener.java src/main/java/com/tradingbot/scheduler/WatchlistScheduler.java
git commit -m "feat(order): thread conviction from TradeIdea through order placement"
```

---

### Task 5: AlpacaClient — detect which bracket leg closed

**Files:**
- Modify: `src/main/java/com/tradingbot/alpaca/AlpacaClient.java` (add a method; the class already has `apiKey`, `apiSecret`, `http`, `tradingBaseUrl`)

- [ ] **Step 1: Add a `CloseLeg` enum and `getBracketCloseLeg` method**

Add near the other public methods (e.g. after `getAllOrderStatuses`):

```java
    /** Which leg of a bracket closed the position. NONE = still open / entry unfilled. */
    public enum CloseLeg { NONE, STOP, TARGET }

    /**
     * Inspects a bracket order's child legs (nested) and reports whether the stop-loss or
     * take-profit leg has filled. Returns NONE if neither has filled yet.
     */
    public CloseLeg getBracketCloseLeg(String orderId) throws IOException {
        String url = tradingBaseUrl + "/v2/orders/" + orderId + "?nested=true";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", apiSecret)
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) return CloseLeg.NONE;
            JsonObject parent = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (!parent.has("legs") || parent.get("legs").isJsonNull()) return CloseLeg.NONE;
            for (JsonElement el : parent.getAsJsonArray("legs")) {
                JsonObject leg = el.getAsJsonObject();
                String status = leg.has("status") ? leg.get("status").getAsString() : "";
                if (!"filled".equals(status)) continue;
                String type = leg.has("type") ? leg.get("type").getAsString() : "";
                // Take-profit legs are limit orders; stop-loss legs are stop / stop_limit.
                if (type.contains("stop")) return CloseLeg.STOP;
                if (type.contains("limit")) return CloseLeg.TARGET;
            }
            return CloseLeg.NONE;
        }
    }
```

- [ ] **Step 2: Verify compile**

Run: `mvn package -q -DskipTests`
Expected: `EXIT=0`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tradingbot/alpaca/AlpacaClient.java
git commit -m "feat(alpaca): getBracketCloseLeg detects stop vs take-profit fill"
```

---

### Task 6: KrakenClient — signed QueryOrders status lookup

**Files:**
- Modify: `src/main/java/com/tradingbot/kraken/KrakenClient.java` (reuses the private `sign(path, nonce, postData)` helper used by `getAccountBalance`)

- [ ] **Step 1: Add `queryOrderStatuses`**

Add near the other private/order methods:

```java
    /**
     * Looks up the status of one or more Kraken order txids via the signed QueryOrders endpoint.
     * Returns txid -> status ("open" | "closed" | "canceled" | "expired"). Missing/error txids
     * are omitted. Note: a Kraken entry order shows "closed" once it FILLS (entry executed), not
     * when stopped out — the attached stop-loss-close order has its own txid we don't hold, so
     * stop-outs are inferred elsewhere from the position disappearing.
     */
    public java.util.Map<String, String> queryOrderStatuses(java.util.List<String> txids) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (!hasCredentials || txids == null || txids.isEmpty()) return out;
        java.util.List<String> clean = new java.util.ArrayList<>();
        for (String t : txids) if (t != null && !t.isBlank() && !t.equals("n/a")) clean.add(t);
        if (clean.isEmpty()) return out;
        try {
            String nonce = String.valueOf(System.currentTimeMillis());
            String postData = "nonce=" + nonce + "&txid=" + String.join(",", clean);
            String signature = sign("/0/private/QueryOrders", nonce, postData);
            RequestBody body = RequestBody.create(postData,
                    okhttp3.MediaType.parse("application/x-www-form-urlencoded"));
            Request req = new Request.Builder()
                    .url(BASE + "/0/private/QueryOrders")
                    .addHeader("API-Key", apiKey)
                    .addHeader("API-Sign", signature)
                    .post(body)
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                JsonArray errors = root.getAsJsonArray("error");
                if (errors != null && errors.size() > 0) {
                    log.warn("Kraken QueryOrders error: {}", errors);
                    return out;
                }
                JsonObject result = root.getAsJsonObject("result");
                if (result == null) return out;
                for (java.util.Map.Entry<String, JsonElement> e : result.entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    if (o.has("status")) out.put(e.getKey(), o.get("status").getAsString());
                }
                return out;
            }
        } catch (Exception e) {
            log.error("Kraken QueryOrders failed", e);
            return out;
        }
    }
```

- [ ] **Step 2: Verify compile**

Run: `mvn package -q -DskipTests`
Expected: `EXIT=0`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tradingbot/kraken/KrakenClient.java
git commit -m "feat(kraken): signed QueryOrders status lookup"
```

---

### Task 7: OutcomeTracker — poll, detect closes, record outcomes

**Files:**
- Create: `src/main/java/com/tradingbot/order/OutcomeTracker.java`

- [ ] **Step 1: Write the class**

```java
package com.tradingbot.order;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.db.OrderDao;
import com.tradingbot.db.OrderDao.OpenOrder;
import com.tradingbot.db.PerformanceDao;
import com.tradingbot.kraken.KrakenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls placed bracket orders and records a realized outcome when a position closes, populating
 * position_outcomes so the setup_performance view (fed into the LLM prompt) has real data.
 *
 * <p>Exit price is taken from the stored stop/target level (decision: "from stop/target levels"),
 * not the actual fill — so PnL ignores exit slippage. Alpaca: which leg filled is read from the
 * nested bracket. Kraken: the take-profit leg is read via QueryOrders; a stop-out is inferred when
 * the entry has filled but the position is no longer open.
 */
public class OutcomeTracker {

    private static final Logger log = LoggerFactory.getLogger(OutcomeTracker.class);
    private static final int POLL_INTERVAL_SECONDS = 60;

    private final AlpacaClient alpaca;
    private final KrakenClient kraken;       // may be null if crypto not configured
    private final OrderDao orderDao;
    private final PerformanceDao performanceDao;

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outcome-tracker");
        t.setDaemon(true);
        return t;
    });

    public OutcomeTracker(AlpacaClient alpaca, KrakenClient kraken,
                          OrderDao orderDao, PerformanceDao performanceDao) {
        this.alpaca = alpaca;
        this.kraken = kraken;
        this.orderDao = orderDao;
        this.performanceDao = performanceDao;
    }

    public void start() {
        poller.scheduleAtFixedRate(this::poll, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("OutcomeTracker started — polling every {}s", POLL_INTERVAL_SECONDS);
    }

    public void shutdown() {
        poller.shutdownNow();
    }

    private void poll() {
        List<OpenOrder> orders;
        try {
            orders = orderDao.listOpenForOutcome();
        } catch (Exception e) {
            log.warn("OutcomeTracker: could not load open orders: {}", e.getMessage());
            return;
        }
        if (orders.isEmpty()) return;

        // Fetch Kraken open positions once per poll for the stop-out inference path.
        Map<String, Double> krakenPositions = (kraken != null) ? safeKrakenPositions() : Map.of();

        for (OpenOrder o : orders) {
            try {
                if ("ALPACA".equalsIgnoreCase(o.broker())) {
                    handleAlpaca(o);
                } else if ("KRAKEN".equalsIgnoreCase(o.broker())) {
                    handleKraken(o, krakenPositions);
                }
            } catch (Exception e) {
                log.warn("OutcomeTracker: failed on order {} ({}): {}", o.id(), o.ticker(), e.getMessage());
            }
        }
    }

    private void handleAlpaca(OpenOrder o) throws Exception {
        AlpacaClient.CloseLeg leg = alpaca.getBracketCloseLeg(o.orderId());
        if (leg == AlpacaClient.CloseLeg.NONE) return;
        double exit = (leg == AlpacaClient.CloseLeg.TARGET) ? o.target() : o.stop();
        record(o, exit, leg == AlpacaClient.CloseLeg.TARGET);
    }

    private void handleKraken(OpenOrder o, Map<String, Double> positions) {
        if (kraken == null) return;
        Map<String, String> statuses =
                kraken.queryOrderStatuses(List.of(o.orderId(), o.tpOrderId() == null ? "" : o.tpOrderId()));

        String entryStatus = statuses.get(o.orderId());
        String tpStatus    = o.tpOrderId() != null ? statuses.get(o.tpOrderId()) : null;

        // Take-profit filled → win at target (deterministic via QueryOrders).
        if ("closed".equals(tpStatus)) {
            record(o, o.target(), true);
            return;
        }

        // Entry must have filled (status closed = entry executed) before a stop-out is possible.
        boolean entryFilled = "closed".equals(entryStatus);
        if (!entryFilled) return;

        // Entry filled, TP still open: if the position is gone, it was stopped out → loss at stop.
        boolean positionGone = positions.getOrDefault(o.ticker().toUpperCase(), 0.0) < 1e-8;
        if (positionGone) {
            record(o, o.stop(), false);
        }
    }

    /** Computes PnL from stored levels, writes the outcome, and marks the order closed. */
    private void record(OpenOrder o, double exit, boolean win) {
        boolean isLong = "LONG".equalsIgnoreCase(o.direction());
        double pnl = (isLong ? (exit - o.entry()) : (o.entry() - exit)) * o.qty();
        String outcome = pnl > 0 ? "WIN" : (pnl < 0 ? "LOSS" : "BREAKEVEN");
        // Trust the leg that fired for WIN/LOSS even if rounding makes pnl ~0.
        if (win && "BREAKEVEN".equals(outcome)) outcome = "WIN";
        if (!win && "BREAKEVEN".equals(outcome)) outcome = "LOSS";

        performanceDao.recordOutcome(o.id(), o.ticker(), o.direction(),
                o.entry(), exit, o.qty(), pnl, outcome, o.conviction());
        orderDao.markClosed(o.id());
        log.info("outcome recorded: {} {} {} exit={} pnl={} conviction={}",
                o.ticker(), o.direction(), outcome, String.format("%.4f", exit),
                String.format("%.2f", pnl), o.conviction());
    }

    private Map<String, Double> safeKrakenPositions() {
        try {
            return kraken.getOpenPositions();
        } catch (Exception e) {
            log.warn("OutcomeTracker: Kraken positions unavailable: {}", e.getMessage());
            return Map.of();
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn package -q -DskipTests`
Expected: `EXIT=0`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tradingbot/order/OutcomeTracker.java
git commit -m "feat(order): OutcomeTracker records realized outcomes for both brokers"
```

---

### Task 8: Wire OutcomeTracker into Main

**Files:**
- Modify: `src/main/java/com/tradingbot/Main.java`

- [ ] **Step 1: Construct and start it after `accountMonitor` / `positionSizer` wiring**

Add after the `analysisService.setRiskSizing(...)` line:

```java
        com.tradingbot.order.OutcomeTracker outcomeTracker =
                new com.tradingbot.order.OutcomeTracker(alpaca, kraken, orderDao, performanceDao);
        outcomeTracker.start();
```

- [ ] **Step 2: Shut it down on disconnect**

Find the shutdown block near the end (`scheduler.shutdown(); slippageTracker.shutdown(); db.close();`) and add:

```java
        scheduler.shutdown();
        slippageTracker.shutdown();
        outcomeTracker.shutdown();
        db.close();
```

- [ ] **Step 3: Verify compile**

Run: `mvn package -q -DskipTests`
Expected: `EXIT=0`, fat jar produced.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tradingbot/Main.java
git commit -m "feat: wire OutcomeTracker into startup/shutdown"
```

---

### Task 9: End-to-end verification (run-based) + push

**Files:** none (verification only)

- [ ] **Step 1: Confirm the migration applies at startup**

Run the bot with real `.env`: `java -jar target/trading-bot-1.0-SNAPSHOT.jar`
Expected logs: Flyway applies `V3`, `Bot online in #...`, `OutcomeTracker started — polling every 60s`. No migration errors.

- [ ] **Step 2: Confirm the new columns exist**

Run: `sqlite3 ./trading-bot.db "PRAGMA table_info(position_outcomes);" && sqlite3 ./trading-bot.db "PRAGMA table_info(orders);"`
Expected: both list a `conviction` column.

- [ ] **Step 3: Place a fast-resolving crypto trade**

In Discord (Kraken configured), place a tiny bracket whose target is very close to current price so it fills quickly, e.g.:
`!play BTC/USD LONG e=<near-mid> s=<just below> t=<just above> qty=<small>`
Expected: confirmation message; a row in `orders` with `broker='KRAKEN'`, `conviction='MANUAL'`, `status='open'`.

- [ ] **Step 4: Wait for a poll cycle and confirm the outcome is recorded**

After the take-profit fills (≤ a couple minutes) wait ~60s, then run:
`sqlite3 ./trading-bot.db "SELECT ticker,direction,outcome,conviction,pnl FROM position_outcomes;"`
Expected: one row (e.g. `BTC/USD|LONG|WIN|MANUAL|<pnl>`). Bot log shows `outcome recorded: ...`. The `orders` row's `status` is now `closed`.

- [ ] **Step 5: Confirm the feedback view returns data**

Run: `sqlite3 ./trading-bot.db "SELECT * FROM setup_performance;"`
Expected: a non-empty row grouping by direction+conviction (e.g. `LONG|MANUAL|1|1|100.0|<avg_pnl>`).

- [ ] **Step 6: Confirm the win-rate context reaches the LLM prompt**

Run `!analyze BTC/USD` and confirm (via the analysis output or DEBUG logs of the technical prompt) that the `### Historical hit-rate of past setups` fragment now appears. (It was empty before any outcomes existed.)

- [ ] **Step 7: Push the branch**

```bash
git push
```

---

## Notes / known caveats (document, do not block)

- **Exit-slippage ignored:** PnL uses the stored stop/target level, not the actual fill (per decision). Realized PnL will differ slightly from broker statements.
- **Kraken orphaned leg:** when one leg fills, the other (stop or TP) may remain open on Kraken. This plan does not cancel the orphan — add a `kraken.cancelOrder(otherTxid)` follow-up if it causes stray orders. (Alpaca brackets auto-cancel the sibling leg, so this only affects Kraken.)
- **Stock equity for sizing** remains a separate open item (Alpaca account-equity accessor) — unrelated to outcome recording.
- **Restart safety:** because conviction and levels live on the `orders` row (not in memory), trades that open before a restart are still recorded after it — the poller reads `listOpenForOutcome()` from the DB each cycle.

## Self-review

- **Spec coverage:** writer for both brokers (Tasks 5/6/7) ✓; exit from stop/target levels (`record()` uses `o.stop()`/`o.target()`) ✓; conviction linkage so the view returns data (Tasks 1-4) ✓; run-based verification (Task 9) ✓; no JUnit added ✓.
- **Type consistency:** `recordOutcome(...)` 9-arg signature defined in Task 2 and called in Task 7 ✓; `OpenOrder` record fields defined in Task 3 used in Task 7 ✓; `CloseLeg` enum defined in Task 5 used in Task 7 ✓; `queryOrderStatuses(List<String>)` defined in Task 6 used in Task 7 ✓; `placePlay`/`placePlayOpg`/`placeCryptoPlay` conviction param defined in Task 4 and all call sites updated ✓.
- **Placeholder scan:** every code step contains complete code; commands have expected output. ✓
