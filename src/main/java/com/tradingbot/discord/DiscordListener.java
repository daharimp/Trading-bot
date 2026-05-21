package com.tradingbot.discord;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.AnalysisService;
import com.tradingbot.chart.ChartRenderer;
import com.tradingbot.order.OrderManager;
import com.tradingbot.scheduler.WatchlistScheduler;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.spec.MessageCreateSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

public class DiscordListener {

    private static final Logger log = LoggerFactory.getLogger(DiscordListener.class);

    private final AnalysisService analysisService;
    private final OrderManager orderManager;
    private final WatchlistScheduler scheduler;
    private final ChartRenderer chartRenderer;
    private final String watchedChannel;

    public DiscordListener(AnalysisService analysisService, OrderManager orderManager,
                           WatchlistScheduler scheduler, ChartRenderer chartRenderer,
                           String watchedChannel) {
        this.analysisService = analysisService;
        this.orderManager    = orderManager;
        this.scheduler       = scheduler;
        this.chartRenderer   = chartRenderer;
        this.watchedChannel  = watchedChannel;
    }

    public Mono<Void> handle(MessageCreateEvent event) {
        Message message = event.getMessage();
        if (message.getAuthor().map(u -> u.isBot()).orElse(true)) return Mono.empty();

        String content = message.getContent().trim().toUpperCase();

        return message.getChannel()
                .filter(ch -> channelMatches(ch, watchedChannel))
                .flatMap(ch -> route(content, ch, message))
                .then();
    }

    private Mono<?> route(String content, MessageChannel ch, Message message) {

        // !ANALYZE AAPL  or  !ANALYZE BTC/USD
        if (content.startsWith("!ANALYZE ")) {
            String ticker = content.substring("!ANALYZE ".length()).trim();
            if (!ticker.matches("[A-Z0-9/]{1,10}")) {
                return ch.createMessage("❌ Invalid ticker `" + ticker + "`. Example: `!analyze AAPL` or `!analyze BTC/USD`");
            }
            return ch.createMessage("🔍 Analyzing **" + ticker + "** — technical + fundamental running in parallel...")
                    .flatMap(status ->
                        Mono.fromCallable(() -> runAnalysisWithChart(ticker))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(result -> {
                                if (result.chartPng != null) {
                                    return status.getChannel().flatMap(c -> c.createMessage(
                                            MessageCreateSpec.builder()
                                                    .content(result.text)
                                                    .addFile("chart.png", new ByteArrayInputStream(result.chartPng))
                                                    .build()));
                                }
                                return status.getChannel().flatMap(c -> c.createMessage(result.text));
                            })
                    )
                    .onErrorResume(e -> {
                        log.error("Error analyzing {}", content, e);
                        return ch.createMessage("❌ Error: " + e.getMessage());
                    });
        }

        // !WATCH AAPL TSLA BTC/USD
        if (content.startsWith("!WATCH ")) {
            String[] tickers = content.substring("!WATCH ".length()).trim().split("\\s+");
            for (String t : tickers) scheduler.addTicker(t);
            return ch.createMessage("📋 Added to watchlist: **" + String.join(", ", tickers) + "**\n"
                    + "Current watchlist: " + String.join(", ", scheduler.getWatchlist()));
        }

        // !UNWATCH AAPL
        if (content.startsWith("!UNWATCH ")) {
            String ticker = content.substring("!UNWATCH ".length()).trim();
            scheduler.removeTicker(ticker);
            List<String> remaining = scheduler.getWatchlist();
            String list = remaining.isEmpty() ? "*(empty)*" : String.join(", ", remaining);
            return ch.createMessage("✅ Removed **" + ticker + "** from watchlist.\nCurrent: " + list);
        }

        // !WATCHLIST
        if (content.equals("!WATCHLIST")) {
            List<String> list = scheduler.getWatchlist();
            if (list.isEmpty()) return ch.createMessage("📋 Watchlist is empty. Add tickers with `!watch AAPL TSLA`");
            return ch.createMessage("📋 **Watchlist:** " + String.join(", ", list)
                    + "\nOvernight analysis runs at the configured schedule. Use `!runanalysis` to trigger now.");
        }

        // !RUNANALYSIS — trigger now
        if (content.equals("!RUNANALYSIS")) {
            if (scheduler.getWatchlist().isEmpty()) {
                return ch.createMessage("📋 Watchlist is empty. Add tickers first with `!watch AAPL TSLA`");
            }
            scheduler.runNow();
            return ch.createMessage("🚀 Overnight analysis triggered — results will be posted shortly.");
        }

        // !PLAY AAPL LONG E=182.50 S=179 T=189 QTY=10
        if (content.startsWith("!PLAY ")) {
            return Mono.fromCallable(() -> parseAndPlaceOrder(content))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ch::createMessage)
                    .onErrorResume(e -> ch.createMessage("❌ Order failed: " + e.getMessage()));
        }

        // !POSITIONS
        if (content.equals("!POSITIONS")) {
            return Mono.fromCallable(orderManager::listPositions)
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ch::createMessage)
                    .onErrorResume(e -> ch.createMessage("❌ Could not fetch positions: " + e.getMessage()));
        }

        // !CANCEL ORDER_ID
        if (content.startsWith("!CANCEL ")) {
            String orderId = content.substring("!CANCEL ".length()).trim();
            return Mono.fromCallable(() -> orderManager.cancelOrder(orderId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ch::createMessage)
                    .onErrorResume(e -> ch.createMessage("❌ Cancel failed: " + e.getMessage()));
        }

        // !HELP
        if (content.equals("!HELP")) {
            return ch.createMessage(helpText());
        }

        return Mono.empty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private record AnalysisResult(String text, byte[] chartPng) {}

    private AnalysisResult runAnalysisWithChart(String ticker) {
        String text = analysisService.runFullAnalysis(ticker);

        byte[] chart = null;
        try {
            Map<AlpacaClient.Timeframe, BarSeries> seriesMap = analysisService.fetchSeriesMap(ticker);
            BarSeries series = seriesMap.getOrDefault(AlpacaClient.Timeframe.H1,
                    seriesMap.isEmpty() ? null : seriesMap.values().iterator().next());
            if (series != null) {
                chart = chartRenderer.render(ticker, series);
            }
        } catch (Exception e) {
            log.warn("Chart rendering failed for {}: {}", ticker, e.getMessage());
        }

        return new AnalysisResult(text, chart);
    }

    private String parseAndPlaceOrder(String content) throws Exception {
        // !PLAY AAPL LONG E=182.50 S=179 T=189 QTY=10
        String[] parts = content.substring("!PLAY ".length()).trim().split("\\s+");
        if (parts.length < 6) {
            return "❌ Usage: `!play AAPL LONG e=182.50 s=179 t=189 qty=10`";
        }

        String ticker    = parts[0];
        String direction = parts[1]; // LONG or SHORT

        double entry = 0, stop = 0, target = 0;
        int qty = 0;

        for (int i = 2; i < parts.length; i++) {
            String[] kv = parts[i].split("=");
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "E" -> entry  = Double.parseDouble(kv[1]);
                case "S" -> stop   = Double.parseDouble(kv[1]);
                case "T" -> target = Double.parseDouble(kv[1]);
                case "QTY" -> qty  = Integer.parseInt(kv[1]);
            }
        }

        if (!direction.equals("LONG") && !direction.equals("SHORT")) {
            return "❌ Direction must be `LONG` or `SHORT`.";
        }
        if (entry == 0 || stop == 0 || target == 0 || qty == 0) {
            return "❌ Missing parameters. Usage: `!play AAPL LONG e=182.50 s=179 t=189 qty=10`";
        }

        return orderManager.placePlay(ticker, direction, entry, stop, target, qty);
    }

    private boolean channelMatches(MessageChannel ch, String name) {
        if (name == null || name.isBlank()) return true;
        if (ch instanceof GuildMessageChannel gmc) {
            return gmc.getName().equalsIgnoreCase(name);
        }
        return false;
    }

    private String helpText() {
        return """
                **Trading Bot Commands**
                `!analyze AAPL` — Full technical + fundamental analysis with chart
                `!analyze BTC/USD` — Crypto analysis (no fundamentals)
                `!watch AAPL TSLA BTC/USD` — Add tickers to overnight watchlist
                `!unwatch AAPL` — Remove ticker from watchlist
                `!watchlist` — Show current watchlist
                `!runanalysis` — Trigger overnight analysis now
                `!play AAPL LONG e=182.50 s=179 t=189 qty=10` — Place bracket order
                `!positions` — Show open positions and pending orders
                `!cancel ORDER_ID` — Cancel a pending order
                `!help` — Show this message
                """;
    }
}
