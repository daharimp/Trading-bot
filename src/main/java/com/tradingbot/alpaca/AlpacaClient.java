package com.tradingbot.alpaca;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BarSeries;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AlpacaClient {

    public enum Timeframe {
        M5("5Min", "5m"),
        M15("15Min", "15m"),
        H1("1Hour", "1H"),
        H4("4Hour", "4H"),
        D1("1Day", "1D");

        public final String alpacaCode;
        public final String displayName;

        Timeframe(String alpacaCode, String displayName) {
            this.alpacaCode = alpacaCode;
            this.displayName = displayName;
        }
    }

    private static final String DATA_BASE         = "https://data.alpaca.markets";
    private static final String PAPER_TRADING_BASE = "https://paper-api.alpaca.markets";
    private static final String LIVE_TRADING_BASE  = "https://api.alpaca.markets";

    private static final Set<String> CRYPTO_SYMBOLS = Set.of(
            "BTC", "ETH", "SOL", "DOGE", "AVAX", "MATIC", "LINK", "UNI", "ADA", "XRP", "LTC", "BCH"
    );

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String apiSecret;
    private final String tradingBaseUrl;
    private final OkHttpClient http;
    private com.tradingbot.kraken.KrakenClient krakenClient;

    public AlpacaClient(String apiKey, String apiSecret, String mode) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.tradingBaseUrl = "live".equalsIgnoreCase(mode) ? LIVE_TRADING_BASE : PAPER_TRADING_BASE;
        this.http = new OkHttpClient();
    }

    /** Injects the Kraken client for crypto data routing. Call once after construction. */
    public void setKrakenClient(com.tradingbot.kraken.KrakenClient kraken) {
        this.krakenClient = kraken;
    }

    /** Creates a SlippageTracker pre-wired with this client's HTTP client and trading URL. */
    public com.tradingbot.order.SlippageTracker newSlippageTracker() {
        return new com.tradingbot.order.SlippageTracker(http, apiKey, apiSecret, tradingBaseUrl);
    }

    // ── Crypto detection ────────────────────────────────────────────────────────

    public static boolean isCrypto(String ticker) {
        return ticker.contains("/") || CRYPTO_SYMBOLS.contains(ticker.toUpperCase());
    }

    public static String normalizeCryptoTicker(String ticker) {
        String upper = ticker.toUpperCase();
        return upper.contains("/") ? upper : upper + "/USD";
    }

    // ── Market data ─────────────────────────────────────────────────────────────

    /**
     * Smart dispatch: routes crypto to KrakenClient (if wired) or Alpaca crypto endpoint,
     * and stocks to the Alpaca stock bars endpoint.
     */
    public BarSeries getBars(String ticker, Timeframe timeframe) throws IOException {
        if (isCrypto(ticker)) {
            if (krakenClient != null) {
                return krakenClient.getBars(ticker, timeframe);
            }
            return getCryptoBars(normalizeCryptoTicker(ticker), timeframe);
        }
        return getStockBars(ticker, timeframe);
    }

    public BarSeries getStockBars(String ticker, Timeframe timeframe) throws IOException {
        int limit = barLimit(timeframe);
        String start = lookbackStart(timeframe, limit);

        for (String feed : new String[]{"sip", "iex"}) {
            String url = String.format(
                    "%s/v2/stocks/%s/bars?timeframe=%s&limit=%d&adjustment=raw&feed=%s&start=%s",
                    DATA_BASE, ticker, timeframe.alpacaCode, limit, feed, start
            );
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("APCA-API-KEY-ID", apiKey)
                    .addHeader("APCA-API-SECRET-KEY", apiSecret)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) continue;
                String body = response.body().string();
                BarSeries series = parseBars(ticker + "_" + timeframe.displayName, body);
                if (series.getBarCount() > 0) return series;
            }
        }
        throw new IOException("No bar data returned for " + ticker + " on any feed");
    }

    /**
     * Fetches bars for multiple stock symbols in a single request.
     * Symbols that return no data are omitted from the result map.
     */
    public Map<String, BarSeries> getStockBarsBatch(List<String> symbols, Timeframe timeframe) throws IOException {
        if (symbols.isEmpty()) return Map.of();
        int limit = barLimit(timeframe);
        String start = lookbackStart(timeframe, limit);
        String symbolsParam = URLEncoder.encode(String.join(",", symbols), StandardCharsets.UTF_8);

        Map<String, BarSeries> result = new LinkedHashMap<>();
        for (String feed : new String[]{"sip", "iex"}) {
            String url = String.format(
                    "%s/v2/stocks/bars?symbols=%s&timeframe=%s&limit=%d&adjustment=raw&feed=%s&start=%s",
                    DATA_BASE, symbolsParam, timeframe.alpacaCode, limit, feed, start
            );
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("APCA-API-KEY-ID", apiKey)
                    .addHeader("APCA-API-SECRET-KEY", apiSecret)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) continue;
                String body = response.body().string();
                // Multi-symbol response: {"bars": {"AAPL": [...], "TSLA": [...]}}
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (!root.has("bars") || !root.get("bars").isJsonObject()) continue;
                JsonObject barsBySymbol = root.getAsJsonObject("bars");
                for (String sym : symbols) {
                    if (result.containsKey(sym)) continue; // already fetched from sip
                    if (!barsBySymbol.has(sym)) continue;
                    JsonObject normalized = new JsonObject();
                    normalized.add("bars", barsBySymbol.getAsJsonArray(sym));
                    BarSeries series = parseBars(sym + "_" + timeframe.displayName, normalized.toString());
                    if (series.getBarCount() > 0) result.put(sym, series);
                }
                if (result.size() == symbols.size()) break; // all symbols resolved
            }
        }
        return result;
    }

    public BarSeries getCryptoBars(String pair, Timeframe timeframe) throws IOException {
        int limit = barLimit(timeframe);
        String start = lookbackStart(timeframe, limit);

        String encodedSymbol = URLEncoder.encode(pair, StandardCharsets.UTF_8);
        String url = String.format(
                "%s/v1beta3/crypto/us/bars?symbols=%s&timeframe=%s&limit=%d&start=%s",
                DATA_BASE, encodedSymbol, timeframe.alpacaCode, limit, start
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", apiSecret)
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Alpaca crypto bars HTTP " + response.code() + " for " + pair);
            }
            String body = response.body().string();
            // v1beta3 returns {"bars": {"BTC/USD": [...]}}; unwrap to the stock-shaped {"bars": [...]}
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject barsBySymbol = root.has("bars") && root.get("bars").isJsonObject()
                    ? root.getAsJsonObject("bars") : new JsonObject();
            JsonArray bars = barsBySymbol.has(pair) && barsBySymbol.get(pair).isJsonArray()
                    ? barsBySymbol.getAsJsonArray(pair) : new JsonArray();
            JsonObject normalized = new JsonObject();
            normalized.add("bars", bars);
            BarSeries series = parseBars(pair + "_" + timeframe.displayName, normalized.toString());
            if (series.getBarCount() == 0) {
                throw new IOException("No crypto bar data returned for " + pair);
            }
            return series;
        }
    }

    private static int barLimit(Timeframe timeframe) {
        return switch (timeframe) {
            case M5, M15, H1 -> 100;
            case H4, D1 -> 60;
        };
    }

    private static String lookbackStart(Timeframe timeframe, int limit) {
        Duration perBar = switch (timeframe) {
            case M5  -> Duration.ofMinutes(5);
            case M15 -> Duration.ofMinutes(15);
            case H1  -> Duration.ofHours(1);
            case H4  -> Duration.ofHours(4);
            case D1  -> Duration.ofDays(1);
        };
        // Multiply by 4 to absorb weekends, holidays, and after-hours gaps so `limit` bars are reachable.
        ZonedDateTime start = ZonedDateTime.now(java.time.ZoneOffset.UTC).minus(perBar.multipliedBy(limit * 4L));
        return start.format(DateTimeFormatter.ISO_INSTANT);
    }

    // ── Trading API ──────────────────────────────────────────────────────────────

    public record Quote(double bid, double ask, double mid) {}

    /**
     * Fetches the latest NBBO quote for a stock symbol.
     * Returns null if the quote is unavailable.
     */
    public Quote getLatestQuote(String symbol) throws IOException {
        String url = DATA_BASE + "/v2/stocks/" + symbol + "/quotes/latest?feed=iex";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", apiSecret)
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            JsonObject quote = root.has("quote") ? root.getAsJsonObject("quote") : null;
            if (quote == null) return null;
            double bid = quote.has("bp") ? quote.get("bp").getAsDouble() : 0;
            double ask = quote.has("ap") ? quote.get("ap").getAsDouble() : 0;
            if (bid <= 0 || ask <= 0) return null;
            return new Quote(bid, ask, (bid + ask) / 2.0);
        }
    }

    /**
     * Places a bracket order with the given time-in-force. Returns the Alpaca order ID.
     * tif: "gtc" (default for manual trades), "day", "opg" (limit-on-open for scheduled entries).
     */
    public String placeOrder(String symbol, String side, double limitPrice,
                              double stopPrice, double takeProfitPrice, int qty,
                              String tif) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("qty", String.valueOf(qty));
        body.addProperty("side", side);
        body.addProperty("type", "limit");
        body.addProperty("time_in_force", tif);
        body.addProperty("limit_price", String.format("%.2f", limitPrice));
        body.addProperty("order_class", "bracket");

        JsonObject stopLoss = new JsonObject();
        stopLoss.addProperty("stop_price", String.format("%.2f", stopPrice));
        body.add("stop_loss", stopLoss);

        JsonObject takeProfit = new JsonObject();
        takeProfit.addProperty("limit_price", String.format("%.2f", takeProfitPrice));
        body.add("take_profit", takeProfit);

        Request request = new Request.Builder()
                .url(tradingBaseUrl + "/v2/orders")
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", apiSecret)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                JsonObject err = JsonParser.parseString(responseBody).getAsJsonObject();
                String msg = err.has("message") ? err.get("message").getAsString() : responseBody;
                throw new IOException("Order placement failed: " + msg);
            }
            JsonObject result = JsonParser.parseString(responseBody).getAsJsonObject();
            return result.get("id").getAsString();
        }
    }

    /** Backwards-compatible overload — defaults to gtc. */
    public String placeOrder(String symbol, String side, double limitPrice,
                              double stopPrice, double takeProfitPrice, int qty) throws IOException {
        return placeOrder(symbol, side, limitPrice, stopPrice, takeProfitPrice, qty, "gtc");
    }

    /**
     * Returns a formatted summary of open positions for Discord.
     */
    public String getPositionsSummary() throws IOException {
        Request request = new Request.Builder()
                .url(tradingBaseUrl + "/v2/positions")
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", apiSecret)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch positions: HTTP " + response.code());
            }
            JsonArray positions = JsonParser.parseString(body).getAsJsonArray();
            if (positions.isEmpty()) return "No open positions.";

            StringBuilder sb = new StringBuilder("**Open Positions:**\n");
            for (JsonElement el : positions) {
                JsonObject p = el.getAsJsonObject();
                String sym  = p.get("symbol").getAsString();
                String qty  = p.get("qty").getAsString();
                String side = p.get("side").getAsString();
                String pl   = p.get("unrealized_pl").getAsString();
                String pct  = p.get("unrealized_plpc").getAsString();
                double pctVal = Double.parseDouble(pct) * 100;
                sb.append(String.format("• **%s** %s %s shares | P&L: $%s (%.1f%%)\n",
                        sym, side.toUpperCase(), qty, pl, pctVal));
            }
            return sb.toString();
        }
    }

    /**
     * Returns a formatted summary of open/pending orders for Discord.
     */
    public String getOrdersSummary() throws IOException {
        Request request = new Request.Builder()
                .url(tradingBaseUrl + "/v2/orders?status=open")
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", apiSecret)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch orders: HTTP " + response.code());
            }
            JsonArray orders = JsonParser.parseString(body).getAsJsonArray();
            if (orders.isEmpty()) return "No pending orders.";

            StringBuilder sb = new StringBuilder("**Pending Orders:**\n");
            for (JsonElement el : orders) {
                JsonObject o = el.getAsJsonObject();
                String id     = o.get("id").getAsString().substring(0, 8); // short ID
                String sym    = o.get("symbol").getAsString();
                String side   = o.get("side").getAsString();
                String qty    = o.get("qty").getAsString();
                String status = o.get("status").getAsString();
                String type   = o.get("type").getAsString();
                sb.append(String.format("• `%s...` **%s** %s %s shares (%s/%s)\n",
                        id, sym, side.toUpperCase(), qty, type, status));
            }
            return sb.toString();
        }
    }

    /**
     * Fetches all orders (any status) and returns a map of orderId -> status.
     * Used at boot to reconcile the local DB against Alpaca's ground truth.
     */
    public Map<String, String> getAllOrderStatuses() throws IOException {
        Request request = new Request.Builder()
                .url(tradingBaseUrl + "/v2/orders?status=all&limit=500&direction=desc")
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", apiSecret)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch orders: HTTP " + response.code());
            }
            JsonArray orders = JsonParser.parseString(body).getAsJsonArray();
            Map<String, String> result = new LinkedHashMap<>();
            for (JsonElement el : orders) {
                JsonObject o = el.getAsJsonObject();
                String id     = o.get("id").getAsString();
                String status = o.get("status").getAsString();
                result.put(id, status);
            }
            return result;
        }
    }

    /**
     * Cancels a pending order by ID.
     */
    public void cancelOrder(String orderId) throws IOException {
        Request request = new Request.Builder()
                .url(tradingBaseUrl + "/v2/orders/" + orderId)
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", apiSecret)
                .delete()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 204) {
                String body = response.body().string();
                throw new IOException("Cancel failed (HTTP " + response.code() + "): " + body);
            }
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────────

    private BarSeries parseBars(String seriesName, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray bars = root.has("bars") && !root.get("bars").isJsonNull()
                ? root.getAsJsonArray("bars") : new JsonArray();

        List<Bar> barList = new ArrayList<>();
        for (JsonElement el : bars) {
            JsonObject b = el.getAsJsonObject();
            ZonedDateTime time = ZonedDateTime.parse(
                    b.get("t").getAsString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            double open   = b.get("o").getAsDouble();
            double high   = b.get("h").getAsDouble();
            double low    = b.get("l").getAsDouble();
            double close  = b.get("c").getAsDouble();
            double volume = b.get("v").getAsDouble();

            barList.add(BaseBar.builder()
                    .timePeriod(Duration.ofMinutes(1))
                    .endTime(time)
                    .openPrice(org.ta4j.core.num.DecimalNum.valueOf(open))
                    .highPrice(org.ta4j.core.num.DecimalNum.valueOf(high))
                    .lowPrice(org.ta4j.core.num.DecimalNum.valueOf(low))
                    .closePrice(org.ta4j.core.num.DecimalNum.valueOf(close))
                    .volume(org.ta4j.core.num.DecimalNum.valueOf(volume))
                    .build());
        }

        BaseBarSeries series = new BaseBarSeries(seriesName);
        for (Bar bar : barList) {
            series.addBar(bar);
        }
        return series;
    }
}
