package com.tradingbot.discord;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.FundamentalAnalyst;
import com.tradingbot.analysis.IndicatorEngine;
import com.tradingbot.analysis.TechnicalAnalyst;
import com.tradingbot.analysis.TradeIdeaGenerator;
import com.tradingbot.fundamental.FundamentalDataClient;
import com.tradingbot.model.FundamentalData;
import com.tradingbot.model.TradeIdea;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.object.entity.channel.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DiscordListener {

    private static final Logger log = LoggerFactory.getLogger(DiscordListener.class);

    private static final AlpacaClient.Timeframe[] TIMEFRAMES = {
            AlpacaClient.Timeframe.M5,
            AlpacaClient.Timeframe.M15,
            AlpacaClient.Timeframe.H1,
            AlpacaClient.Timeframe.H4,
            AlpacaClient.Timeframe.D1
    };

    private final AlpacaClient alpaca;
    private final FundamentalDataClient fdClient;
    private final TradeIdeaGenerator rulesEngine;
    private final TechnicalAnalyst techAnalyst;
    private final FundamentalAnalyst fundAnalyst;
    private final String watchedChannel;

    public DiscordListener(AlpacaClient alpaca, FundamentalDataClient fdClient,
                           TechnicalAnalyst techAnalyst, FundamentalAnalyst fundAnalyst,
                           String watchedChannel) {
        this.alpaca = alpaca;
        this.fdClient = fdClient;
        this.rulesEngine = new TradeIdeaGenerator();
        this.techAnalyst = techAnalyst;
        this.fundAnalyst = fundAnalyst;
        this.watchedChannel = watchedChannel;
    }

    public Mono<Void> handle(MessageCreateEvent event) {
        Message message = event.getMessage();

        if (message.getAuthor().map(u -> u.isBot()).orElse(true)) {
            return Mono.empty();
        }

        String content = message.getContent().trim().toUpperCase();

        if (!content.startsWith("!ANALYZE ")) {
            return Mono.empty();
        }

        String ticker = content.substring("!ANALYZE ".length()).trim();
        if (!ticker.matches("[A-Z]{1,5}")) {
            return message.getChannel()
                    .flatMap(ch -> ch.createMessage("❌ Invalid ticker `" + ticker + "`. Use letters only, e.g. `!analyze AAPL`"))
                    .then();
        }

        return message.getChannel()
                .filter(ch -> channelMatches(ch, watchedChannel))
                .flatMap(ch -> ch.createMessage("🔍 Analyzing **" + ticker + "** — technical (GPT-4o) + fundamental (GPT-4o-mini) running in parallel..."))
                .flatMap(statusMsg ->
                    // Run the blocking analysis off the event loop thread
                    Mono.fromCallable(() -> runFullAnalysis(ticker))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(response ->
                            statusMsg.getChannel().flatMap(ch -> ch.createMessage(response))
                        )
                )
                .onErrorResume(e -> {
                    log.error("Error analyzing {}", ticker, e);
                    return message.getChannel()
                            .flatMap(ch -> ch.createMessage(
                                    "❌ Error analyzing **" + ticker + "**: " + e.getMessage()));
                })
                .then();
    }

    private String runFullAnalysis(String ticker) {
        // Step 1: Fetch bars for all timeframes
        Map<AlpacaClient.Timeframe, BarSeries> seriesMap = new LinkedHashMap<>();
        for (AlpacaClient.Timeframe tf : TIMEFRAMES) {
            try {
                seriesMap.put(tf, alpaca.getBars(ticker, tf));
            } catch (Exception e) {
                log.warn("Could not fetch {} {} bars: {}", ticker, tf.displayName, e.getMessage());
            }
        }

        if (seriesMap.isEmpty()) {
            return "❌ Could not fetch any price data for **" + ticker + "**. " +
                   "Check the ticker symbol and try again.";
        }

        // Step 2: Rules engine generates candidate setups per timeframe
        Map<AlpacaClient.Timeframe, List<TradeIdea>> candidates = new LinkedHashMap<>();
        for (var entry : seriesMap.entrySet()) {
            List<TradeIdea> ideas = rulesEngine.generate(ticker, entry.getKey(), entry.getValue());
            if (!ideas.isEmpty()) {
                candidates.put(entry.getKey(), ideas);
            }
        }

        // Step 3: Run technical and fundamental analysis in parallel
        CompletableFuture<List<TradeIdea>> techFuture = CompletableFuture.supplyAsync(
                () -> techAnalyst.analyze(ticker, seriesMap, candidates));

        CompletableFuture<String> fundFuture = CompletableFuture.supplyAsync(() -> {
            try {
                FundamentalData fd = fdClient.fetch(ticker);
                return fundAnalyst.analyze(ticker, fd);
            } catch (IOException e) {
                log.warn("Fundamental data fetch failed for {}: {}", ticker, e.getMessage());
                return "⚠️ Fundamental data unavailable: " + e.getMessage();
            }
        });

        List<TradeIdea> finalIdeas = techFuture.join();
        String fundamentalSummary = fundFuture.join();

        return buildResponse(ticker, seriesMap, finalIdeas, fundamentalSummary);
    }

    private boolean channelMatches(MessageChannel ch, String name) {
        if (name == null || name.isBlank()) return true;
        if (ch instanceof GuildMessageChannel gmc) {
            return gmc.getName().equalsIgnoreCase(name);
        }
        return false;
    }

    private String buildResponse(String ticker,
                                  Map<AlpacaClient.Timeframe, BarSeries> seriesMap,
                                  List<TradeIdea> ideas,
                                  String fundamentalSummary) {
        StringBuilder sb = new StringBuilder();

        // Header with current price snapshot
        BarSeries latestSeries = seriesMap.get(AlpacaClient.Timeframe.M5);
        if (latestSeries == null) latestSeries = seriesMap.values().iterator().next();
        IndicatorEngine snap = new IndicatorEngine(latestSeries);

        sb.append(String.format("📊 **%s** | $%.2f | RSI %.0f | ATR %.2f\n",
                ticker, snap.currentPrice(), snap.rsi(), snap.atr()));
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        if (ideas.isEmpty()) {
            sb.append("\n🤷 **GPT-4o found no high-probability setups right now.**\n");
            sb.append("Price may be in no-man's land or between key levels.\n");
            sb.append("Try again after the next major candle close.\n");
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📋 **FUNDAMENTAL ANALYSIS (GPT-4o-mini)**\n\n");
            sb.append(fundamentalSummary).append("\n");
            sb.append("\n⚠️ *AI-generated analysis — not financial advice. Manage your own risk.*");
            return sb.toString();
        }

        sb.append(String.format("**GPT-4o identified %d setup(s):**\n", ideas.size()));

        String[] tfOrder = {"5m", "15m", "1H", "4H", "1D"};
        for (String tf : tfOrder) {
            List<TradeIdea> tfIdeas = ideas.stream()
                    .filter(i -> i.getTimeframe().equals(tf))
                    .toList();
            if (!tfIdeas.isEmpty()) {
                sb.append(String.format("\n**── %s ──**\n", tf));
                for (TradeIdea idea : tfIdeas) {
                    sb.append(idea.formatForDiscord());
                }
            }
        }

        // Any ideas with unrecognized timeframe labels
        List<TradeIdea> other = ideas.stream()
                .filter(i -> !List.of(tfOrder).contains(i.getTimeframe()))
                .toList();
        for (TradeIdea idea : other) {
            sb.append(idea.formatForDiscord());
        }

        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📋 **FUNDAMENTAL ANALYSIS (GPT-4o-mini)**\n\n");
        sb.append(fundamentalSummary).append("\n");
        sb.append("\n⚠️ *AI-generated analysis — not financial advice. Manage your own risk.*");
        return sb.toString();
    }
}
