package com.tradingbot.order;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks intended order prices and logs slippage when fills are detected.
 * Throwaway scaffold — M2 replaces this with the fills table.
 */
public class SlippageTracker {

    private static final Logger log = LoggerFactory.getLogger(SlippageTracker.class);
    private static final int POLL_INTERVAL_SECONDS = 30;

    private record IntendedOrder(String symbol, String side, double intendedPrice, Instant submittedAt) {}

    private final ConcurrentHashMap<String, IntendedOrder> pending = new ConcurrentHashMap<>();
    private final OkHttpClient http;
    private final String apiKey;
    private final String apiSecret;
    private final String tradingBaseUrl;
    private final Instant startedAt = Instant.now();

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "slippage-poller");
        t.setDaemon(true);
        return t;
    });

    public SlippageTracker(OkHttpClient http, String apiKey, String apiSecret, String tradingBaseUrl) {
        this.http = http;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.tradingBaseUrl = tradingBaseUrl;
    }

    public void start() {
        poller.scheduleAtFixedRate(this::poll, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void shutdown() {
        poller.shutdownNow();
    }

    /** Call immediately after submitting an order. */
    public void record(String orderId, String symbol, String side, double intendedPrice) {
        pending.put(orderId, new IntendedOrder(symbol, side, intendedPrice, Instant.now()));
    }

    private void poll() {
        if (pending.isEmpty()) return;
        try {
            String after = DateTimeFormatter.ISO_INSTANT.format(startedAt);
            String url = tradingBaseUrl + "/v2/orders?status=closed&after=" + after + "&limit=100";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("APCA-API-KEY-ID", apiKey)
                    .addHeader("APCA-API-SECRET-KEY", apiSecret)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) return;
                JsonArray orders = JsonParser.parseString(response.body().string()).getAsJsonArray();
                for (JsonElement el : orders) {
                    JsonObject o = el.getAsJsonObject();
                    String id = o.get("id").getAsString();
                    IntendedOrder intended = pending.remove(id);
                    if (intended == null) continue;

                    String status = o.get("status").getAsString();
                    if (!status.equals("filled") && !status.equals("partially_filled")) continue;

                    JsonElement filledPriceEl = o.get("filled_avg_price");
                    if (filledPriceEl == null || filledPriceEl.isJsonNull()) continue;

                    double filledPrice = filledPriceEl.getAsDouble();
                    double slippageBps = (filledPrice - intended.intendedPrice())
                            / intended.intendedPrice() * 10_000;
                    // For shorts, positive slippage means filled lower (good) — flip sign
                    if (intended.side().equalsIgnoreCase("sell")) slippageBps = -slippageBps;

                    long latencyMs = Instant.now().toEpochMilli() - intended.submittedAt().toEpochMilli();

                    log.info("slippage: {} side={} intended=${} filled=${} slippage_bps={} latency_ms={}",
                            intended.symbol(),
                            intended.side().toUpperCase(),
                            String.format("%.2f", intended.intendedPrice()),
                            String.format("%.2f", filledPrice),
                            String.format("%.1f", slippageBps),
                            latencyMs);
                }
            }
        } catch (Exception e) {
            log.warn("Slippage poll failed: {}", e.getMessage());
        }
    }
}
