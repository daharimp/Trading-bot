package com.tradingbot.kraken;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tradingbot.alpaca.AlpacaClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BarSeries;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kraken REST API client for crypto bar data (public) and order placement (private/signed).
 * Alpaca remains the broker for stocks; this client handles all crypto data and execution.
 */
public class KrakenClient {

    private static final Logger log = LoggerFactory.getLogger(KrakenClient.class);

    private static final String BASE = "https://api.kraken.com";

    // Kraken uses legacy pair names that differ from our internal representation.
    // Internal: "BTC", "BTC/USD", "ETH/USD"  →  Kraken OHLC pair: "XBTUSD"
    private static final Map<String, String> PAIR_MAP = Map.ofEntries(
            Map.entry("BTC",      "XBTUSD"),
            Map.entry("BTC/USD",  "XBTUSD"),
            Map.entry("ETH",      "XETHZUSD"),
            Map.entry("ETH/USD",  "XETHZUSD"),
            Map.entry("SOL",      "SOLUSD"),
            Map.entry("SOL/USD",  "SOLUSD"),
            Map.entry("DOGE",     "XDGUSD"),
            Map.entry("DOGE/USD", "XDGUSD"),
            Map.entry("AVAX",     "AVAXUSD"),
            Map.entry("AVAX/USD", "AVAXUSD"),
            Map.entry("MATIC",    "MATICUSD"),
            Map.entry("MATIC/USD","MATICUSD"),
            Map.entry("LINK",     "LINKUSD"),
            Map.entry("LINK/USD", "LINKUSD"),
            Map.entry("UNI",      "UNIUSD"),
            Map.entry("UNI/USD",  "UNIUSD"),
            Map.entry("ADA",      "ADAUSD"),
            Map.entry("ADA/USD",  "ADAUSD"),
            Map.entry("XRP",      "XXRPZUSD"),
            Map.entry("XRP/USD",  "XXRPZUSD"),
            Map.entry("LTC",      "XLTCZUSD"),
            Map.entry("LTC/USD",  "XLTCZUSD"),
            Map.entry("BCH",      "BCHUSD"),
            Map.entry("BCH/USD",  "BCHUSD"),
            Map.entry("HYPE",     "HYPEUSD"),
            Map.entry("HYPE/USD", "HYPEUSD")
    );

    // Kraken OHLC intervals in minutes
    private static final Map<AlpacaClient.Timeframe, Integer> INTERVAL_MAP = Map.of(
            AlpacaClient.Timeframe.M5,  5,
            AlpacaClient.Timeframe.M15, 15,
            AlpacaClient.Timeframe.H1,  60,
            AlpacaClient.Timeframe.H4,  240,
            AlpacaClient.Timeframe.D1,  1440
    );

    private final OkHttpClient http;
    private final String apiKey;
    private final String apiSecret;
    private final boolean hasCredentials;

    public KrakenClient(OkHttpClient http, String apiKey, String apiSecret) {
        this.http = http;
        this.apiKey = apiKey != null ? apiKey : "";
        this.apiSecret = apiSecret != null ? apiSecret : "";
        this.hasCredentials = !this.apiKey.isBlank() && !this.apiSecret.isBlank();
    }

    /** Translates an internal ticker (e.g. "BTC", "BTC/USD") to a Kraken pair string. */
    public static String toKrakenPair(String ticker) {
        String pair = PAIR_MAP.get(ticker.toUpperCase());
        if (pair == null) {
            // Best-effort: strip "/" and append USD if not already there
            String upper = ticker.toUpperCase().replace("/", "");
            pair = upper.endsWith("USD") ? upper : upper + "USD";
        }
        return pair;
    }

    // ── Market data (public, no auth) ────────────────────────────────────────────

    /**
     * Fetches OHLC bars for a crypto ticker from Kraken's public REST API.
     * ticker: internal format — "BTC", "ETH/USD", etc.
     */
    public BarSeries getBars(String ticker, AlpacaClient.Timeframe timeframe) throws IOException {
        String pair = toKrakenPair(ticker);
        int interval = INTERVAL_MAP.getOrDefault(timeframe, 60);
        int limit = barLimit(timeframe);
        // `since` is a Unix timestamp; go back far enough to retrieve `limit` bars
        long sinceTs = Instant.now().getEpochSecond() - (long) interval * 60 * limit * 4;

        String url = String.format("%s/0/public/OHLC?pair=%s&interval=%d&since=%d",
                BASE, URLEncoder.encode(pair, StandardCharsets.UTF_8), interval, sinceTs);

        Request request = new Request.Builder().url(url).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Kraken OHLC HTTP " + response.code() + " for " + pair);
            }
            String body = response.body().string();
            return parseOhlc(ticker + "_" + timeframe.displayName, pair, body, limit);
        }
    }

    public record Quote(double bid, double ask, double mid) {}

    /**
     * Fetches the current best bid/ask for a crypto ticker from Kraken's public Ticker endpoint.
     * Returns null if the quote is unavailable.
     */
    public Quote getLatestQuote(String ticker) throws IOException {
        String pair = toKrakenPair(ticker);
        String url = BASE + "/0/public/Ticker?pair=" + URLEncoder.encode(pair, StandardCharsets.UTF_8);

        Request request = new Request.Builder().url(url).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (root.has("error") && root.getAsJsonArray("error").size() > 0) return null;
            JsonObject result = root.getAsJsonObject("result");
            if (result == null || result.isEmpty()) return null;

            // result is keyed by the actual Kraken pair name (may differ from what we sent)
            JsonObject ticker_data = result.entrySet().iterator().next().getValue().getAsJsonObject();
            // "b": [bestBid, wholeLotVolume, lotVolume]
            // "a": [bestAsk, wholeLotVolume, lotVolume]
            double bid = ticker_data.getAsJsonArray("b").get(0).getAsDouble();
            double ask = ticker_data.getAsJsonArray("a").get(0).getAsDouble();
            if (bid <= 0 || ask <= 0) return null;
            return new Quote(bid, ask, (bid + ask) / 2.0);
        }
    }

    // ── Order placement (private, HMAC-SHA512 signed) ────────────────────────────

    /**
     * Places a bracket-style order on Kraken as two separate signed requests:
     *   1. A GTC limit entry order with a conditional stop-loss-limit close.
     *   2. A separate GTC limit order at the take-profit target (opposite side).
     * direction: "LONG" or "SHORT"
     * Returns "entryTxid/tpTxid" — both IDs joined with "/".
     */
    public String placeOrder(String ticker, String direction, double entry,
                              double stop, double target, int qty) throws Exception {
        if (!hasCredentials) {
            throw new IllegalStateException("Kraken order placement requires KRAKEN_API_KEY and KRAKEN_API_SECRET");
        }

        String pair     = toKrakenPair(ticker);
        String entryType = direction.equalsIgnoreCase("LONG") ? "buy" : "sell";
        String tpType    = direction.equalsIgnoreCase("LONG") ? "sell" : "buy";

        log.info("Placing Kraken {} bracket: {} {} @ {} stop {} target {}",
                direction, qty, pair,
                String.format("%.8f", entry),
                String.format("%.8f", stop),
                String.format("%.8f", target));

        String entryTxid = addOrder(pair, entryType, qty, entry, stop);
        String tpTxid    = addOrder(pair, tpType,    qty, target, 0);

        return entryTxid + "/" + tpTxid;
    }

    public Map<String, Double> getAccountBalance() {
        if (!hasCredentials) return Map.of();
        try {
            String nonce = String.valueOf(System.currentTimeMillis());
            String postData = "nonce=" + nonce;
            String signature = sign("/0/private/Balance", nonce, postData);
            RequestBody body = RequestBody.create(postData, okhttp3.MediaType.parse("application/x-www-form-urlencoded"));
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
                if (!errors.isEmpty()) { log.warn("Kraken balance error: {}", errors); return Map.of(); }
                Map<String, Double> balances = new LinkedHashMap<>();
                JsonObject result = root.getAsJsonObject("result");
                for (Map.Entry<String, JsonElement> e : result.entrySet()) {
                    balances.put(e.getKey(), Double.parseDouble(e.getValue().getAsString()));
                }
                return balances;
            }
        } catch (Exception e) { log.error("Failed to fetch Kraken balance", e); return Map.of(); }
    }

    public Map<String, Double> getOpenPositions() {
        Map<String, Double> balances = getAccountBalance();
        Map<String, Double> positions = new LinkedHashMap<>();
        Map<String, String> assetToTicker = Map.ofEntries(
            Map.entry("XXBT","BTC"), Map.entry("XETH","ETH"), Map.entry("SOL","SOL"),
            Map.entry("XDOGE","DOGE"), Map.entry("AVAX","AVAX"), Map.entry("MATIC","MATIC"),
            Map.entry("LINK","LINK"), Map.entry("UNI","UNI"), Map.entry("ADA","ADA"),
            Map.entry("XXRP","XRP"), Map.entry("XLTC","LTC"), Map.entry("BCH","BCH"),
            Map.entry("HYPE","HYPE")
        );
        for (Map.Entry<String, Double> e : balances.entrySet()) {
            String asset = e.getKey(); double qty = e.getValue();
            if (qty < 0.00001) continue;
            if (asset.equals("ZUSD")) continue;
            positions.put(assetToTicker.getOrDefault(asset, asset), qty);
        }
        return positions;
    }

    public int cancelAllOpenOrders() {
        if (!hasCredentials) return 0;
        try {
            String nonce = String.valueOf(System.currentTimeMillis());
            String postData = "nonce=" + nonce;
            String signature = sign("/0/private/CancelAll", nonce, postData);
            RequestBody body = RequestBody.create(postData, okhttp3.MediaType.parse("application/x-www-form-urlencoded"));
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
                if (!errors.isEmpty()) { log.warn("Kraken cancelAll error: {}", errors); return 0; }
                return root.getAsJsonObject("result").get("count").getAsInt();
            }
        } catch (Exception e) { log.error("Failed to cancel all Kraken orders", e); return 0; }
    }

    public String closePosition(String ticker) {
        if (!hasCredentials) return "No Kraken credentials configured.";
        try {
            Map<String, Double> positions = getOpenPositions();
            Double qty = positions.get(ticker.toUpperCase());
            if (qty == null || qty < 0.00001) return "No open position found for " + ticker;
            String pair = toKrakenPair(ticker);
            if (pair == null) return "Unsupported ticker: " + ticker;
            String nonce = String.valueOf(System.currentTimeMillis());
            String volume = String.format("%.8f", qty).replaceAll("0+$", "").replaceAll("\\.$", ".0");
            String postData = "nonce=" + nonce + "&ordertype=market&type=sell&volume=" + volume + "&pair=" + pair;
            String signature = sign("/0/private/AddOrder", nonce, postData);
            RequestBody body = RequestBody.create(postData, okhttp3.MediaType.parse("application/x-www-form-urlencoded"));
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
                if (!errors.isEmpty()) return "Kraken error: " + errors.get(0).getAsString();
                double midPrice = 0;
                try { midPrice = getLatestQuote(ticker).mid(); } catch (Exception ignored) {}
                return String.format("Sold %.6f %s at market (~$%.2f)", qty, ticker.toUpperCase(), midPrice);
            }
        } catch (Exception e) { log.error("Failed to close position for {}", ticker, e); return "Error closing position: " + e.getMessage(); }
    }

    /** Sends a single AddOrder request. If stop > 0 a conditional stop-loss-limit close is attached. */
    private String addOrder(String pair, String type, int qty, double price, double stop) throws Exception {
        String nonce = String.valueOf(System.currentTimeMillis());
        StringBuilder pd = new StringBuilder()
                .append("nonce=").append(nonce)
                .append("&ordertype=limit")
                .append("&type=").append(type)
                .append("&volume=").append(qty)
                .append("&pair=").append(URLEncoder.encode(pair, StandardCharsets.UTF_8))
                .append("&price=").append(String.format("%.8f", price))
                .append("&timeinforce=GTC");

        if (stop > 0) {
            pd.append("&close%5Bordertype%5D=stop-loss-limit")
              .append("&close%5Bprice%5D=").append(String.format("%.8f", stop))
              .append("&close%5Bprice2%5D=").append(String.format("%.8f", stop));
        }

        String path = "/0/private/AddOrder";
        String sig  = sign(path, nonce, pd.toString());

        Request request = new Request.Builder()
                .url(BASE + path)
                .addHeader("API-Key", apiKey)
                .addHeader("API-Sign", sig)
                .post(RequestBody.create(pd.toString(),
                        okhttp3.MediaType.get("application/x-www-form-urlencoded")))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Kraken AddOrder HTTP " + response.code() + ": " + body);
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray errors = root.getAsJsonArray("error");
            if (errors != null && errors.size() > 0) {
                throw new IOException("Kraken order error: " + errors);
            }
            return root.getAsJsonObject("result").getAsJsonArray("txid").get(0).getAsString();
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────────

    private BarSeries parseOhlc(String seriesName, String pair, String json, int limit) throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray errors = root.getAsJsonArray("error");
        if (errors != null && errors.size() > 0) {
            throw new IOException("Kraken OHLC error: " + errors);
        }
        JsonObject result = root.getAsJsonObject("result");
        if (result == null) throw new IOException("No result in Kraken OHLC response for " + pair);

        // result contains the actual pair data (key may differ from requested pair, e.g. XXBTZUSD)
        JsonArray ohlcData = null;
        for (Map.Entry<String, JsonElement> entry : result.entrySet()) {
            if (entry.getValue().isJsonArray()) {
                ohlcData = entry.getValue().getAsJsonArray();
                break;
            }
        }
        if (ohlcData == null || ohlcData.isEmpty()) {
            throw new IOException("No OHLC data returned for " + pair);
        }

        // Kraken OHLC row: [time, open, high, low, close, vwap, volume, count]
        // Take the last `limit` bars (most recent)
        int start = Math.max(0, ohlcData.size() - limit);
        BaseBarSeries series = new BaseBarSeries(seriesName);
        for (int i = start; i < ohlcData.size(); i++) {
            JsonArray row = ohlcData.get(i).getAsJsonArray();
            long ts     = row.get(0).getAsLong();
            double open  = Double.parseDouble(row.get(1).getAsString());
            double high  = Double.parseDouble(row.get(2).getAsString());
            double low   = Double.parseDouble(row.get(3).getAsString());
            double close = Double.parseDouble(row.get(4).getAsString());
            double volume = Double.parseDouble(row.get(6).getAsString());
            ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneOffset.UTC);

            series.addBar(BaseBar.builder()
                    .timePeriod(Duration.ofMinutes(1))
                    .endTime(time)
                    .openPrice(org.ta4j.core.num.DecimalNum.valueOf(open))
                    .highPrice(org.ta4j.core.num.DecimalNum.valueOf(high))
                    .lowPrice(org.ta4j.core.num.DecimalNum.valueOf(low))
                    .closePrice(org.ta4j.core.num.DecimalNum.valueOf(close))
                    .volume(org.ta4j.core.num.DecimalNum.valueOf(volume))
                    .build());
        }
        return series;
    }

    private static int barLimit(AlpacaClient.Timeframe timeframe) {
        return switch (timeframe) {
            case M5, M15, H1 -> 100;
            case H4, D1 -> 60;
        };
    }

    // ── HMAC-SHA512 request signing ───────────────────────────────────────────────

    private String sign(String path, String nonce, String postData) throws Exception {
        byte[] decodedSecret = Base64.getDecoder().decode(apiSecret);

        // SHA256(nonce + postData)
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(nonce.getBytes(StandardCharsets.UTF_8));
        byte[] sha256Hash = sha256.digest(postData.getBytes(StandardCharsets.UTF_8));

        // HMAC-SHA512(path + SHA256, decodedSecret)
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(decodedSecret, "HmacSHA512"));
        mac.update(path.getBytes(StandardCharsets.UTF_8));
        mac.update(sha256Hash);
        return Base64.getEncoder().encodeToString(mac.doFinal());
    }
}
