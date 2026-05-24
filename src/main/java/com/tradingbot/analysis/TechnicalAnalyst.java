package com.tradingbot.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.model.TradeIdea;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class TechnicalAnalyst {

    private static final Logger log = LoggerFactory.getLogger(TechnicalAnalyst.class);

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int MAX_TOKENS = 2000;

    private static final String SYSTEM_PROMPT = """
            You are an expert equities trader and technical analyst with 15 years of experience
            in day trading and swing trading. You will receive:
            1. A summary of technical indicator values across multiple timeframes for a given ticker.
            2. A list of candidate trade setups identified by a rules-based engine.

            Your job is to:
            - Evaluate each setup critically using all timeframe data provided.
            - Confirm, modify, or reject each setup based on your analysis.
            - Add context the rules engine cannot: macro timeframe alignment, divergences,
              overextension, key level proximity, volume character, and risk factors.
            - Rate conviction as HIGH, MEDIUM, or LOW with clear justification.
            - Rewrite the rationale for each confirmed setup in clear, actionable trader language.
            - If you see a better setup the rules engine missed, add it.
            - Give a 5 Star rating to each setup based on how compelling it is (⭐️⭐️⭐️⭐️⭐️ = very strong, ⭐️ = weak).
            - Use the Technical indicator data to adjust the entry, stop, and target levels if needed.
            - Look for patterns.
            - Look for divergences and on all timeframes for confirmations in plays as well.


            Format your response EXACTLY like this for each setup (one per block):

            ---SETUP---
            TICKER: {ticker}
            TIMEFRAME: {timeframe}
            DIRECTION: LONG or SHORT
            ENTRY: {price as a single number, e.g. 85.35 — no commentary, ranges, or units on this line}
            STOP: {price as a single number, e.g. 84.10}
            TARGET: {price as a single number, e.g. 89.00}
            CONVICTION: HIGH | MEDIUM | LOW
            RATIONALE: {2-3 sentences of analyst commentary}
            RISK: {1 sentence on what would invalidate this trade}
            ---END---

            If no setups are worth taking, respond with exactly: NO_SETUPS
            """;

    private final OkHttpClient http;
    private final String apiKey;
    private final String model;

    public TechnicalAnalyst(String anthropicApiKey, String model) {
        this.apiKey = anthropicApiKey;
        this.model = model;
        this.http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(120))
                .readTimeout(Duration.ofSeconds(120))
                .build();
    }

    public List<TradeIdea> analyze(
            String ticker,
            Map<AlpacaClient.Timeframe, BarSeries> seriesMap,
            Map<AlpacaClient.Timeframe, List<TradeIdea>> candidatesByTimeframe) {

        String userPrompt = buildUserPrompt(ticker, seriesMap, candidatesByTimeframe);

        try {
            String response = callAnthropic(userPrompt);

            if (response.equals("NO_SETUPS") || response.isBlank()) {
                return List.of();
            }

            return parseResponse(ticker, response);

        } catch (Exception e) {
            log.error("Anthropic technical analysis failed for {}: {}", ticker, e.getMessage());
            return candidatesByTimeframe.values().stream()
                    .flatMap(List::stream)
                    .toList();
        }
    }

    private String callAnthropic(String userPrompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", MAX_TOKENS);
        body.addProperty("system", SYSTEM_PROMPT);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(ANTHROPIC_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response resp = http.newCall(request).execute()) {
            ResponseBody respBody = resp.body();
            String raw = respBody != null ? respBody.string() : "";
            if (!resp.isSuccessful()) {
                throw new RuntimeException("Anthropic API error " + resp.code() + ": " + raw);
            }
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray content = json.getAsJsonArray("content");
            StringBuilder text = new StringBuilder();
            for (var element : content) {
                JsonObject block = element.getAsJsonObject();
                if ("text".equals(block.get("type").getAsString())) {
                    text.append(block.get("text").getAsString());
                }
            }
            return text.toString().trim();
        }
    }

    private String buildUserPrompt(
            String ticker,
            Map<AlpacaClient.Timeframe, BarSeries> seriesMap,
            Map<AlpacaClient.Timeframe, List<TradeIdea>> candidatesByTimeframe) {

        StringBuilder sb = new StringBuilder();
        sb.append("## Ticker: ").append(ticker).append("\n\n");
        sb.append("### Indicator Snapshot by Timeframe\n\n");

        for (AlpacaClient.Timeframe tf : AlpacaClient.Timeframe.values()) {
            BarSeries series = seriesMap.get(tf);
            if (series == null || series.getBarCount() < 10) continue;

            IndicatorEngine eng = new IndicatorEngine(series);
            sb.append(String.format("**%s** (%d bars)\n", tf.displayName, series.getBarCount()));
            sb.append(String.format("  Price: $%.2f\n", eng.currentPrice()));
            sb.append(String.format("  EMA9: %.2f | EMA21: %.2f | EMA50: %.2f\n",
                    eng.ema9(), eng.ema21(), eng.ema50()));
            sb.append(String.format("  RSI(14): %.1f\n", eng.rsi()));
            sb.append(String.format("  ATR(14): %.2f (%.1f%% of price)\n",
                    eng.atr(), (eng.atr() / eng.currentPrice()) * 100));
            sb.append(String.format("  Volume: %.0f | 20-bar avg: %.0f | Ratio: %.2fx\n",
                    eng.currentVolume(), eng.avgVolume20(),
                    eng.avgVolume20() > 0 ? eng.currentVolume() / eng.avgVolume20() : 0));
            sb.append(String.format("  EMA stack: %s\n",
                    eng.isBullishEmaStack() ? "BULLISH (9>21>50)"
                    : eng.isBearishEmaStack() ? "BEARISH (9<21<50)" : "MIXED"));
            sb.append(String.format("  20-bar swing high: %.2f | low: %.2f\n\n",
                    eng.swingHigh(20), eng.swingLow(20)));
        }

        sb.append("### Candidate Setups from Rules Engine\n\n");

        boolean anySetups = false;
        for (var entry : candidatesByTimeframe.entrySet()) {
            for (TradeIdea idea : entry.getValue()) {
                anySetups = true;
                sb.append(String.format(
                        "- [%s] %s %s | Entry: $%.2f | Stop: $%.2f | Target: $%.2f | R:R 1:%.1f | %s\n",
                        idea.getTimeframe(), idea.getDirection(), ticker,
                        idea.getEntry(), idea.getStopLoss(), idea.getTarget(),
                        idea.getRiskRewardRatio(), idea.getRationale()));
            }
        }
        if (!anySetups) {
            sb.append("None identified by the rules engine.\n");
        }

        sb.append("\nPlease provide your analysis.");
        return sb.toString();
    }

    private List<TradeIdea> parseResponse(String ticker, String response) {
        List<TradeIdea> ideas = new java.util.ArrayList<>();

        String[] blocks = response.split("---SETUP---");
        for (String block : blocks) {
            block = block.trim();
            if (!block.contains("---END---")) continue;
            block = block.replace("---END---", "").trim();

            try {
                String timeframe = extractField(block, "TIMEFRAME");
                String dirStr    = extractField(block, "DIRECTION");
                double entry     = extractFirstNumber(extractField(block, "ENTRY"));
                double stop      = extractFirstNumber(extractField(block, "STOP"));
                double target    = extractFirstNumber(extractField(block, "TARGET"));
                String convStr   = extractField(block, "CONVICTION");
                String rationale = extractField(block, "RATIONALE");
                String risk      = extractField(block, "RISK");

                TradeIdea.Direction direction = TradeIdea.Direction.valueOf(dirStr.toUpperCase().trim());
                TradeIdea.Conviction conviction = switch (convStr.toUpperCase().trim()) {
                    case "HIGH" -> TradeIdea.Conviction.HIGH;
                    case "LOW"  -> TradeIdea.Conviction.LOW;
                    default     -> TradeIdea.Conviction.MEDIUM;
                };

                ideas.add(new TradeIdea(ticker, timeframe, direction,
                        entry, stop, target, conviction, rationale + " ⚠️ " + risk));

            } catch (Exception e) {
                log.warn("Failed to parse Claude setup block: {}", e.getMessage());
            }
        }

        return ideas;
    }

    private String extractField(String block, String fieldName) {
        for (String line : block.split("\n")) {
            if (line.startsWith(fieldName + ":")) {
                return line.substring(fieldName.length() + 1).trim();
            }
        }
        return "";
    }

    // Pulls the first numeric token from a field value, tolerating "$", ranges ("86.20–86.31"),
    // commentary ("85.35 (current) or scale-in..."), and stray units. Throws if none found
    // so the surrounding try/catch logs and skips the block.
    private static final java.util.regex.Pattern FIRST_NUMBER =
            java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static double extractFirstNumber(String raw) {
        if (raw == null) throw new NumberFormatException("null field");
        java.util.regex.Matcher m = FIRST_NUMBER.matcher(raw.replace(",", ""));
        if (!m.find()) throw new NumberFormatException("no number in: " + raw);
        return Double.parseDouble(m.group());
    }
}
