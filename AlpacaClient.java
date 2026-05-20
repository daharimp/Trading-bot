package com.tradingbot.alpaca;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BarSeries;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    private static final String PAPER_BASE = "https://data.alpaca.markets";
    private static final String LIVE_BASE  = "https://data.alpaca.markets";

    private final String apiKey;
    private final String apiSecret;
    private final String baseUrl;
    private final OkHttpClient http;

    public AlpacaClient(String apiKey, String apiSecret, String mode) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.baseUrl = "live".equalsIgnoreCase(mode) ? LIVE_BASE : PAPER_BASE;
        this.http = new OkHttpClient();
    }

    /**
     * Fetches OHLCV bars from Alpaca for the given ticker and timeframe.
     * Returns a TA4J BarSeries ready for indicator calculation.
     */
    public BarSeries getBars(String ticker, Timeframe timeframe) throws IOException {
        int limit = switch (timeframe) {
            case M5  -> 100;
            case M15 -> 100;
            case H1  -> 100;
            case H4  -> 60;
            case D1  -> 60;
        };

        // Try sip first (broader coverage), fall back to iex
        for (String feed : new String[]{"sip", "iex"}) {
            String url = String.format(
                    "%s/v2/stocks/%s/bars?timeframe=%s&limit=%d&adjustment=raw&feed=%s",
                    baseUrl, ticker, timeframe.alpacaCode, limit, feed
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
