package com.tradingbot.analysis;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.fundamental.FundamentalDataClient;
import com.tradingbot.model.FundamentalData;
import com.tradingbot.model.TradeIdea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    public record AnalysisResult(String text, List<TradeIdea> ideas) {}

    private static final String[] NUMBER_EMOJIS = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣"};

    private static final AlpacaClient.Timeframe[] TIMEFRAMES = {
            AlpacaClient.Timeframe.M5,
            AlpacaClient.Timeframe.M15,
            AlpacaClient.Timeframe.H1,
            AlpacaClient.Timeframe.H4,
            AlpacaClient.Timeframe.D1
    };

    private final AlpacaClient alpaca;
    private final TechnicalAnalyst techAnalyst;
    private final FundamentalAnalyst fundAnalyst;
    private final FundamentalDataClient fdClient;
    private final TradeIdeaGenerator rulesEngine;

    public AnalysisService(AlpacaClient alpaca, TechnicalAnalyst techAnalyst,
                            FundamentalAnalyst fundAnalyst, FundamentalDataClient fdClient) {
        this.alpaca      = alpaca;
        this.techAnalyst = techAnalyst;
        this.fundAnalyst = fundAnalyst;
        this.fdClient    = fdClient;
        this.rulesEngine = new TradeIdeaGenerator();
    }

    /**
     * Runs full multi-timeframe technical + fundamental analysis.
     * Returns an AnalysisResult containing the formatted text and the list of TradeIdeas.
     */
    public AnalysisResult runFullAnalysis(String ticker) {
        boolean crypto = AlpacaClient.isCrypto(ticker);
        String normalizedTicker = crypto ? AlpacaClient.normalizeCryptoTicker(ticker) : ticker;

        // Step 1: Fetch bars for all timeframes
        Map<AlpacaClient.Timeframe, BarSeries> seriesMap = new LinkedHashMap<>();
        for (AlpacaClient.Timeframe tf : TIMEFRAMES) {
            try {
                seriesMap.put(tf, alpaca.getBars(normalizedTicker, tf));
            } catch (Exception e) {
                log.warn("Could not fetch {} {} bars: {}", normalizedTicker, tf.displayName, e.getMessage());
            }
        }

        if (seriesMap.isEmpty()) {
            String msg = "❌ Could not fetch any price data for **" + normalizedTicker + "**. " +
                         "Check the ticker symbol and try again.";
            return new AnalysisResult(msg, List.of());
        }

        // Step 2: Rules engine generates candidate setups per timeframe
        Map<AlpacaClient.Timeframe, List<TradeIdea>> candidates = new LinkedHashMap<>();
        for (var entry : seriesMap.entrySet()) {
            List<TradeIdea> ideas = rulesEngine.generate(normalizedTicker, entry.getKey(), entry.getValue());
            if (!ideas.isEmpty()) candidates.put(entry.getKey(), ideas);
        }

        // Step 3: Technical + fundamental in parallel
        CompletableFuture<List<TradeIdea>> techFuture = CompletableFuture.supplyAsync(
                () -> techAnalyst.analyze(normalizedTicker, seriesMap, candidates));

        CompletableFuture<String> fundFuture = CompletableFuture.supplyAsync(() -> {
            if (crypto) return "ℹ️ Fundamental analysis not available for crypto assets.";
            try {
                FundamentalData fd = fdClient.fetch(normalizedTicker);
                return fundAnalyst.analyze(normalizedTicker, fd);
            } catch (IOException e) {
                log.warn("Fundamental data fetch failed for {}: {}", normalizedTicker, e.getMessage());
                return "⚠️ Fundamental data unavailable: " + e.getMessage();
            }
        });

        List<TradeIdea> finalIdeas = techFuture.join();
        String fundamentalSummary  = fundFuture.join();

        String text = buildResponse(normalizedTicker, seriesMap, finalIdeas, fundamentalSummary);
        return new AnalysisResult(text, finalIdeas);
    }

    /**
     * Builds the numbered pick menu shown after analysis.
     * Returns empty string if there are no ideas.
     */
    public String buildPickMenu(List<TradeIdea> ideas) {
        if (ideas == null || ideas.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("📋 **Pick a setup to trade:**\n");
        int limit = Math.min(ideas.size(), NUMBER_EMOJIS.length);
        for (int i = 0; i < limit; i++) {
            TradeIdea idea = ideas.get(i);
            String conviction = switch (idea.getConviction()) {
                case HIGH   -> "🔥 HIGH";
                case MEDIUM -> "⚡ MEDIUM";
                case LOW    -> "💤 LOW";
            };
            sb.append(String.format("%s  **%s** %s | Entry $%.2f | Stop $%.2f | Target $%.2f | R:R %.1f | %s\n",
                    NUMBER_EMOJIS[i],
                    idea.getDirection(), idea.getTimeframe(),
                    idea.getEntry(), idea.getStopLoss(), idea.getTarget(),
                    idea.getRiskRewardRatio(), conviction));
        }
        sb.append("\nReply `!pick N qty=10` to place as a bracket order.");
        return sb.toString();
    }

    /**
     * Returns the bar series map for a ticker (used by DiscordListener for chart rendering).
     */
    public Map<AlpacaClient.Timeframe, BarSeries> fetchSeriesMap(String ticker) {
        String normalizedTicker = AlpacaClient.isCrypto(ticker)
                ? AlpacaClient.normalizeCryptoTicker(ticker) : ticker;
        Map<AlpacaClient.Timeframe, BarSeries> seriesMap = new LinkedHashMap<>();
        for (AlpacaClient.Timeframe tf : TIMEFRAMES) {
            try {
                seriesMap.put(tf, alpaca.getBars(normalizedTicker, tf));
            } catch (Exception e) {
                log.warn("Could not fetch {} {} bars: {}", normalizedTicker, tf.displayName, e.getMessage());
            }
        }
        return seriesMap;
    }

    private String buildResponse(String ticker,
                                  Map<AlpacaClient.Timeframe, BarSeries> seriesMap,
                                  List<TradeIdea> ideas,
                                  String fundamentalSummary) {
        StringBuilder sb = new StringBuilder();

        BarSeries latestSeries = seriesMap.getOrDefault(AlpacaClient.Timeframe.M5,
                seriesMap.values().iterator().next());
        IndicatorEngine snap = new IndicatorEngine(latestSeries);

        sb.append(String.format("📊 **%s** | $%.2f | RSI %.0f | ATR %.2f\n",
                ticker, snap.currentPrice(), snap.rsi(), snap.atr()));
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        if (ideas.isEmpty()) {
            sb.append("\n🤷 **No high-probability setups right now.**\n");
            sb.append("Price may be in no-man's land or between key levels.\n");
            sb.append("Try again after the next major candle close.\n");
        } else {
            sb.append(String.format("**%d setup(s) identified:**\n", ideas.size()));
            String[] tfOrder = {"5m", "15m", "1H", "4H", "1D"};
            for (String tf : tfOrder) {
                List<TradeIdea> tfIdeas = ideas.stream()
                        .filter(i -> i.getTimeframe().equals(tf)).toList();
                if (!tfIdeas.isEmpty()) {
                    sb.append(String.format("\n**── %s ──**\n", tf));
                    for (TradeIdea idea : tfIdeas) sb.append(idea.formatForDiscord());
                }
            }
            List<TradeIdea> other = ideas.stream()
                    .filter(i -> !List.of(tfOrder).contains(i.getTimeframe())).toList();
            for (TradeIdea idea : other) sb.append(idea.formatForDiscord());
        }

        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📋 **FUNDAMENTAL ANALYSIS**\n\n");
        sb.append(fundamentalSummary).append("\n");
        sb.append("\n⚠️ *AI-generated analysis — not financial advice. Manage your own risk.*");
        return sb.toString();
    }
}
