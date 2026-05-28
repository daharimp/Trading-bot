package com.tradingbot.analysis;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.fundamental.FundamentalDataClient;
import com.tradingbot.kraken.KrakenClient;
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

    public record AnalysisResult(String text, List<TradeIdea> ideas,
                                  Map<AlpacaClient.Timeframe, BarSeries> seriesMap) {}

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
    private final SignalScorer scorer = new SignalScorer();
    private KrakenClient kraken;
    private com.tradingbot.order.PositionSizer positionSizer;
    private java.util.function.DoubleSupplier equitySupplier = () -> 0;

    public AnalysisService(AlpacaClient alpaca, TechnicalAnalyst techAnalyst,
                            FundamentalAnalyst fundAnalyst, FundamentalDataClient fdClient) {
        this.alpaca      = alpaca;
        this.techAnalyst = techAnalyst;
        this.fundAnalyst = fundAnalyst;
        this.fdClient    = fdClient;
        this.rulesEngine = new TradeIdeaGenerator();
    }

    /** Injects the Kraken client for crypto bar pre-fetching. Call once after construction. */
    public void setKrakenClient(KrakenClient krakenClient) {
        this.kraken = krakenClient;
    }

    /**
     * Enables risk-based position sizing. When set, each scored idea is annotated with a
     * suggested quantity derived from current account equity and the trade's stop distance.
     */
    public void setRiskSizing(com.tradingbot.order.PositionSizer sizer,
                              java.util.function.DoubleSupplier equitySupplier) {
        this.positionSizer = sizer;
        this.equitySupplier = equitySupplier;
    }

    /**
     * Runs full multi-timeframe technical + fundamental analysis.
     * Returns an AnalysisResult containing the formatted text, trade ideas, and the bar series map.
     */
    public AnalysisResult runFullAnalysis(String ticker) {
        return runFullAnalysis(ticker, null);
    }

    /**
     * Overload that accepts a pre-fetched seriesMap (used by the overnight scheduler to avoid
     * re-fetching bars that were already batch-loaded). Pass null to fetch normally.
     */
    public AnalysisResult runFullAnalysis(String ticker,
                                           Map<AlpacaClient.Timeframe, BarSeries> preloadedSeries) {
        boolean crypto = AlpacaClient.isCrypto(ticker);
        String normalizedTicker = crypto ? AlpacaClient.normalizeCryptoTicker(ticker) : ticker;

        // Step 1: Use pre-loaded bars or fetch individually
        Map<AlpacaClient.Timeframe, BarSeries> seriesMap;
        if (preloadedSeries != null && !preloadedSeries.isEmpty()) {
            seriesMap = preloadedSeries;
        } else {
            seriesMap = new LinkedHashMap<>();
            for (AlpacaClient.Timeframe tf : TIMEFRAMES) {
                try {
                    seriesMap.put(tf, alpaca.getBars(normalizedTicker, tf));
                } catch (Exception e) {
                    log.warn("Could not fetch {} {} bars: {}", normalizedTicker, tf.displayName, e.getMessage());
                }
            }
        }

        if (seriesMap.isEmpty()) {
            String msg = "❌ Could not fetch any price data for **" + normalizedTicker + "**. " +
                         "Check the ticker symbol and try again.";
            return new AnalysisResult(msg, List.of(), Map.of());
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

        CompletableFuture<FundamentalAnalyst.FundamentalResult> fundFuture = CompletableFuture.supplyAsync(() -> {
            if (crypto) {
                return new FundamentalAnalyst.FundamentalResult(
                        "ℹ️ Fundamental analysis not available for crypto assets.", Double.NaN);
            }
            try {
                FundamentalData fd = fdClient.fetch(normalizedTicker);
                return fundAnalyst.analyze(normalizedTicker, fd);
            } catch (IOException e) {
                log.warn("Fundamental data fetch failed for {}: {}", normalizedTicker, e.getMessage());
                return new FundamentalAnalyst.FundamentalResult(
                        "⚠️ Fundamental data unavailable: " + e.getMessage(), Double.NaN);
            }
        });

        List<TradeIdea> finalIdeas = techFuture.join();
        FundamentalAnalyst.FundamentalResult fundResult = fundFuture.join();

        // Step 4: Fuse technical + multi-timeframe + fundamental into a composite conviction,
        // then order ideas best-first so the strongest setups surface at the top.
        scorer.scoreAll(finalIdeas, seriesMap, fundResult.score());
        finalIdeas = finalIdeas.stream()
                .sorted((a, b) -> Double.compare(b.getCompositeScore(), a.getCompositeScore()))
                .toList();

        // Annotate each idea with a risk-sized suggested quantity (display + default for !pick).
        if (positionSizer != null) {
            double equity = equitySupplier.getAsDouble();
            if (equity > 0) {
                for (TradeIdea idea : finalIdeas) {
                    idea.setSuggestedQty(positionSizer.sizeFor(idea, equity));
                }
            }
        }

        String text = buildResponse(normalizedTicker, seriesMap, finalIdeas, fundResult.summary());
        return new AnalysisResult(text, finalIdeas, seriesMap);
    }

    /**
     * Batch-fetches bars for all tickers across all timeframes.
     * Stocks use Alpaca's multi-symbol endpoint (one request per timeframe).
     * Crypto uses KrakenClient if wired, otherwise falls back to Alpaca's crypto endpoint.
     * Returns symbol -> (timeframe -> BarSeries) for use with runFullAnalysis(ticker, preloaded).
     */
    public Map<String, Map<AlpacaClient.Timeframe, BarSeries>> prefetchStockBars(List<String> tickers) {
        List<String> stockTickers  = tickers.stream().filter(t -> !AlpacaClient.isCrypto(t)).toList();
        List<String> cryptoTickers = tickers.stream().filter(AlpacaClient::isCrypto).toList();

        Map<String, Map<AlpacaClient.Timeframe, BarSeries>> result = new LinkedHashMap<>();
        for (String sym : tickers) result.put(sym, new LinkedHashMap<>());

        // Stock batch: one multi-symbol request per timeframe
        for (AlpacaClient.Timeframe tf : TIMEFRAMES) {
            if (!stockTickers.isEmpty()) {
                try {
                    Map<String, BarSeries> batch = alpaca.getStockBarsBatch(stockTickers, tf);
                    batch.forEach((sym, series) -> result.get(sym).put(tf, series));
                } catch (Exception e) {
                    log.warn("Stock batch bar fetch failed for {}: {}", tf.displayName, e.getMessage());
                }
            }
            // Crypto: individual requests via Kraken (or Alpaca fallback)
            for (String sym : cryptoTickers) {
                try {
                    BarSeries series = kraken != null
                            ? kraken.getBars(sym, tf)
                            : alpaca.getBars(sym, tf);
                    result.get(sym).put(tf, series);
                } catch (Exception e) {
                    log.warn("Crypto bar fetch failed for {} {}: {}", sym, tf.displayName, e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * Runs a no-look-ahead backtest of the rules engine for a single ticker/timeframe and
     * returns a Discord-formatted performance summary. Defaults to the 1H timeframe.
     */
    public String runBacktest(String ticker, String timeframeArg) {
        boolean crypto = AlpacaClient.isCrypto(ticker);
        String normalizedTicker = crypto ? AlpacaClient.normalizeCryptoTicker(ticker) : ticker;

        AlpacaClient.Timeframe tf = AlpacaClient.Timeframe.H1;
        if (timeframeArg != null && !timeframeArg.isBlank()) {
            for (AlpacaClient.Timeframe t : AlpacaClient.Timeframe.values()) {
                if (t.displayName.equalsIgnoreCase(timeframeArg.trim())) { tf = t; break; }
            }
        }

        try {
            BarSeries series = crypto && kraken != null
                    ? kraken.getBars(normalizedTicker, tf)
                    : alpaca.getBars(normalizedTicker, tf);
            if (series == null || series.getBarCount() < 60) {
                return "❌ Not enough history to backtest **" + normalizedTicker + "** on " + tf.displayName + ".";
            }
            return new com.tradingbot.backtest.Backtester().run(normalizedTicker, tf, series).formatForDiscord();
        } catch (Exception e) {
            log.warn("Backtest failed for {} {}: {}", normalizedTicker, tf.displayName, e.getMessage());
            return "❌ Backtest failed: " + e.getMessage();
        }
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
            String scorePart = idea.isScored()
                    ? String.format(" | Score %.0f %s", idea.getCompositeScore(), "⭐".repeat(idea.getStars()))
                    : "";
            String qtyPart = idea.getSuggestedQty() > 0
                    ? String.format(" | Qty %d", idea.getSuggestedQty())
                    : "";
            sb.append(String.format("%s  **%s** %s | Entry $%.2f | Stop $%.2f | Target $%.2f | R:R %.1f | %s%s%s\n",
                    NUMBER_EMOJIS[i],
                    idea.getDirection(), idea.getTimeframe(),
                    idea.getEntry(), idea.getStopLoss(), idea.getTarget(),
                    idea.getRiskRewardRatio(), conviction, scorePart, qtyPart));
        }
        sb.append("\nReply `!pick N` to use the risk-sized qty, or `!pick N qty=10` to override.");
        return sb.toString();
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
