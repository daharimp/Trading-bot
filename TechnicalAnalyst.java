package com.tradingbot.analysis;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.ChatModel;
import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.model.TradeIdea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Map;

public class TechnicalAnalyst {

    private static final Logger log = LoggerFactory.getLogger(TechnicalAnalyst.class);

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
            ENTRY: {price}
            STOP: {price}
            TARGET: {price}
            CONVICTION: HIGH | MEDIUM | LOW
            RATIONALE: {2-3 sentences of analyst commentary}
            RISK: {1 sentence on what would invalidate this trade}
            ---END---

            If no setups are worth taking, respond with exactly: NO_SETUPS
            """;

    private final OpenAIClient client;

    public TechnicalAnalyst(String apiKey) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    public List<TradeIdea> analyze(
            String ticker,
            Map<AlpacaClient.Timeframe, BarSeries> seriesMap,
            Map<AlpacaClient.Timeframe, List<TradeIdea>> candidatesByTimeframe) {

        String userPrompt = buildUserPrompt(ticker, seriesMap, candidatesByTimeframe);

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(ChatModel.GPT_4O)
                    .maxCompletionTokens(2000)
                    .addSystemMessage(SYSTEM_PROMPT)
                    .addUserMessage(userPrompt)
                    .build();

            ChatCompletion completion = client.chat().completions().create(params);
            String response = completion.choices().get(0).message().content().orElse("").trim();

            if (response.equals("NO_SETUPS") || response.isBlank()) {
                return List.of();
            }

            return parseResponse(ticker, response);

        } catch (Exception e) {
            log.error("GPT-4o technical analysis failed for {}: {}", ticker, e.getMessage());
            return candidatesByTimeframe.values().stream()
                    .flatMap(List::stream)
                    .toList();
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
                double entry     = Double.parseDouble(extractField(block, "ENTRY").replace("$", ""));
                double stop      = Double.parseDouble(extractField(block, "STOP").replace("$", ""));
                double target    = Double.parseDouble(extractField(block, "TARGET").replace("$", ""));
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
                log.warn("Failed to parse GPT-4o setup block: {}", e.getMessage());
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
}
