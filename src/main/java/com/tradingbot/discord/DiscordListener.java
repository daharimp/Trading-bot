package com.tradingbot.discord;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.AnalysisService;
import com.tradingbot.chart.ChartRenderer;
import com.tradingbot.model.TradeIdea;
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
    private final SessionStore sessionStore;
    private final String watchedChannel;

    public DiscordListener(AnalysisService analysisService, OrderManager orderManager,
                           WatchlistScheduler scheduler, ChartRenderer chartRenderer,
                           SessionStore sessionStore, String watchedChannel) {
        this.analysisService = analysisService;
        this.orderManager    = orderManager;
        this.scheduler       = scheduler;
        this.chartRenderer   = chartRenderer;
        this.sessionStore    = sessionStore;
        this.watchedChannel  = watchedChannel;
    }

    public Mono<Void> handle(MessageCreateEvent event) {
        Message message = event.getMessage();
        if (message.getAuthor().map(u -> u.isBot()).orElse(true)) return Mono.empty();

        String content = message.getContent().trim().toUpperCase();

        return message.getChannel()
                .filter(ch -> channelMatches(ch, watchedChannel))
                .flatMap(ch -> route(content, ch))
                .then();
    }

    private Mono<?> route(String content, MessageChannel ch) {

        // ── !ANALYZE ────────────────────────────────────────────────────────────
        if (content.startsWith("!ANALYZE ")) {
            String ticker = content.substring("!ANALYZE ".length()).trim();
            if (!ticker.matches("[A-Z0-9/]{1,10}")) {
                return ch.createMessage("❌ Invalid ticker `" + ticker
                        + "`. Example: `!analyze AAPL` or `!analyze BTC/USD`");
            }
            return ch.createMessage("🔍 Analyzing **" + ticker
                    + "** — technical + fundamental running in parallel...")
                    .flatMap(status ->
                        Mono.fromCallable(() -> runAnalysisWithChart(ticker))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(result -> {
                                // Store setups for !pick
                                String channelId = ch.getId().asString();
                                sessionStore.store(channelId, result.analysisResult().ideas());

                                // Send analysis + chart, chunked under Discord's 2000-char message limit.
                                // Chart attaches to the first chunk only.
                                Mono<?> analysisMsg = status.getChannel().flatMap(c ->
                                        sendChunked(c, result.analysisResult().text(), result.chartPng()));

                                // Send pick menu as a second message if there are setups
                                String pickMenu = analysisService.buildPickMenu(
                                        result.analysisResult().ideas());
                                if (!pickMenu.isEmpty()) {
                                    return analysisMsg.then(ch.createMessage(pickMenu));
                                }
                                return analysisMsg;
                            })
                    )
                    .onErrorResume(e -> {
                        log.error("Error analyzing {}", content, e);
                        return ch.createMessage("❌ Error: " + e.getMessage());
                    });
        }

        // ── !PICK N QTY=X ───────────────────────────────────────────────────────
        if (content.startsWith("!PICK ")) {
            return Mono.fromCallable(() -> handlePick(content, ch.getId().asString()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ch::createMessage)
                    .onErrorResume(e -> ch.createMessage("❌ Pick failed: " + e.getMessage()));
        }

        // ── !WATCH AAPL [auto] ──────────────────────────────────────────────────
        if (content.startsWith("!WATCH ")) {
            String[] parts = content.substring("!WATCH ".length()).trim().split("\\s+");
            boolean autoPlay = parts.length > 0 && parts[parts.length - 1].equals("AUTO");
            int tickerCount = autoPlay ? parts.length - 1 : parts.length;
            for (int i = 0; i < tickerCount; i++) scheduler.addTicker(parts[i], autoPlay);
            String added = String.join(", ", java.util.Arrays.copyOf(parts, tickerCount));
            return ch.createMessage("📋 Added to watchlist: **" + added + "**"
                    + (autoPlay ? " *(auto-play HIGH setups overnight)*" : "")
                    + "\nCurrent watchlist: " + String.join(", ", scheduler.getWatchlist()));
        }

        // ── !UNWATCH ────────────────────────────────────────────────────────────
        if (content.startsWith("!UNWATCH ")) {
            String ticker = content.substring("!UNWATCH ".length()).trim();
            scheduler.removeTicker(ticker);
            List<String> remaining = scheduler.getWatchlist();
            String list = remaining.isEmpty() ? "*(empty)*" : String.join(", ", remaining);
            return ch.createMessage("✅ Removed **" + ticker + "** from watchlist.\nCurrent: " + list);
        }

        // ── !WATCHLIST ──────────────────────────────────────────────────────────
        if (content.equals("!WATCHLIST")) {
            List<String> list = scheduler.getWatchlist();
            if (list.isEmpty())
                return ch.createMessage("📋 Watchlist is empty. Add tickers with `!watch AAPL TSLA`");
            return ch.createMessage("📋 **Watchlist:** " + String.join(", ", list)
                    + "\n*(auto) = HIGH conviction setups placed automatically overnight*"
                    + "\nUse `!runanalysis` to trigger now.");
        }

        // ── !RUNANALYSIS ────────────────────────────────────────────────────────
        if (content.equals("!RUNANALYSIS")) {
            if (scheduler.isEmpty())
                return ch.createMessage("📋 Watchlist is empty. Add tickers first with `!watch AAPL TSLA`");
            scheduler.runNow();
            return ch.createMessage("🚀 Overnight analysis triggered — results will be posted shortly.");
        }

        // ── !PLAY (manual bracket order) ────────────────────────────────────────
        if (content.startsWith("!PLAY ")) {
            return Mono.fromCallable(() -> parseAndPlaceOrder(content))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ch::createMessage)
                    .onErrorResume(e -> ch.createMessage("❌ Order failed: " + e.getMessage()));
        }

        // ── !POSITIONS ──────────────────────────────────────────────────────────
        if (content.equals("!POSITIONS")) {
            return Mono.fromCallable(orderManager::listPositions)
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ch::createMessage)
                    .onErrorResume(e -> ch.createMessage("❌ Could not fetch positions: " + e.getMessage()));
        }

        // ── !CANCEL ─────────────────────────────────────────────────────────────
        if (content.startsWith("!CANCEL ")) {
            String orderId = content.substring("!CANCEL ".length()).trim();
            return Mono.fromCallable(() -> orderManager.cancelOrder(orderId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ch::createMessage)
                    .onErrorResume(e -> ch.createMessage("❌ Cancel failed: " + e.getMessage()));
        }

        // ── !HELP ───────────────────────────────────────────────────────────────
        if (content.equals("!HELP")) {
            return ch.createMessage(helpText());
        }

        return Mono.empty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private record ChartedResult(AnalysisService.AnalysisResult analysisResult, byte[] chartPng) {}

    private ChartedResult runAnalysisWithChart(String ticker) {
        AnalysisService.AnalysisResult result = analysisService.runFullAnalysis(ticker);

        byte[] chart = null;
        try {
            Map<AlpacaClient.Timeframe, BarSeries> seriesMap = result.seriesMap();
            BarSeries series = seriesMap.getOrDefault(AlpacaClient.Timeframe.H1,
                    seriesMap.isEmpty() ? null : seriesMap.values().iterator().next());
            if (series != null) {
                chart = chartRenderer.render(ticker, series);
            }
        } catch (Exception e) {
            log.warn("Chart rendering failed for {}: {}", ticker, e.getMessage());
        }

        return new ChartedResult(result, chart);
    }

    private String handlePick(String content, String channelId) throws Exception {
        // !PICK 1 QTY=10
        String[] parts = content.substring("!PICK ".length()).trim().split("\\s+");
        if (parts.length < 2) {
            return "❌ Usage: `!pick 1 qty=10`";
        }

        List<TradeIdea> pending = sessionStore.get(channelId);
        if (pending == null || pending.isEmpty()) {
            return "❌ No pending analysis in this channel. Run `!analyze AAPL` first.";
        }

        int n;
        try {
            n = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return "❌ Setup number must be an integer. Example: `!pick 1 qty=10`";
        }

        if (n < 1 || n > pending.size()) {
            return "❌ Setup " + n + " doesn't exist — analysis returned " + pending.size() + " setup(s).";
        }

        int qty = 0;
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=");
            if (kv.length == 2 && kv[0].equals("QTY")) {
                try { qty = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
            }
        }
        if (qty <= 0) {
            return "❌ Must specify quantity. Example: `!pick 1 qty=10`";
        }

        TradeIdea chosen = pending.get(n - 1);
        return orderManager.placePlay(
                chosen.getTicker(),
                chosen.getDirection().name(),
                chosen.getEntry(),
                chosen.getStopLoss(),
                chosen.getTarget(),
                qty);
    }

    private String parseAndPlaceOrder(String content) throws Exception {
        // !PLAY AAPL LONG E=182.50 S=179 T=189 QTY=10
        String[] parts = content.substring("!PLAY ".length()).trim().split("\\s+");
        if (parts.length < 6) return "❌ Usage: `!play AAPL LONG e=182.50 s=179 t=189 qty=10`";

        String ticker    = parts[0];
        String direction = parts[1];
        double entry = 0, stop = 0, target = 0;
        int qty = 0;

        for (int i = 2; i < parts.length; i++) {
            String[] kv = parts[i].split("=");
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "E"   -> entry  = Double.parseDouble(kv[1]);
                case "S"   -> stop   = Double.parseDouble(kv[1]);
                case "T"   -> target = Double.parseDouble(kv[1]);
                case "QTY" -> qty    = Integer.parseInt(kv[1]);
            }
        }

        if (!direction.equals("LONG") && !direction.equals("SHORT"))
            return "❌ Direction must be `LONG` or `SHORT`.";
        if (entry == 0 || stop == 0 || target == 0 || qty == 0)
            return "❌ Missing parameters. Usage: `!play AAPL LONG e=182.50 s=179 t=189 qty=10`";

        return orderManager.placePlay(ticker, direction, entry, stop, target, qty);
    }

    private boolean channelMatches(MessageChannel ch, String name) {
        if (name == null || name.isBlank()) return true;
        if (ch instanceof GuildMessageChannel gmc) return gmc.getName().equalsIgnoreCase(name);
        return false;
    }

    private String helpText() {
        return """
                **Trading Bot Commands**
                `!analyze AAPL` — Full technical + fundamental analysis with chart
                `!analyze BTC/USD` — Crypto analysis (no fundamentals)
                `!pick 1 qty=10` — Place setup #1 from the last analysis as a bracket order
                `!watch AAPL TSLA` — Add tickers to overnight watchlist
                `!watch AAPL auto` — Add with auto-place HIGH conviction setups overnight
                `!unwatch AAPL` — Remove ticker from watchlist
                `!watchlist` — Show current watchlist
                `!runanalysis` — Trigger overnight analysis now
                `!play AAPL LONG e=182.50 s=179 t=189 qty=10` — Manual bracket order
                `!positions` — Show open positions and pending orders
                `!cancel ORDER_ID` — Cancel a pending order
                `!help` — Show this message
                """;
    }

    // Discord hard-caps message content at 2000 chars. Split on paragraph/line boundaries
    // (never mid-word), attach the chart to the first chunk only, and send sequentially.
    private static final int DISCORD_MAX = 1900; // leave headroom for safety

    private static Mono<?> sendChunked(MessageChannel ch, String text, byte[] chartPng) {
        List<String> chunks = chunk(text == null ? "" : text, DISCORD_MAX);
        if (chunks.isEmpty()) chunks = List.of("(empty analysis)");

        Mono<Message> chain = null;
        for (int i = 0; i < chunks.size(); i++) {
            String body = chunks.get(i);
            boolean first = (i == 0);
            Mono<Message> step;
            if (first && chartPng != null) {
                step = ch.createMessage(MessageCreateSpec.builder()
                        .content(body)
                        .addFile("chart.png", new ByteArrayInputStream(chartPng))
                        .build());
            } else {
                step = ch.createMessage(body);
            }
            chain = (chain == null) ? step : chain.then(step);
        }
        return chain;
    }

    private static List<String> chunk(String text, int max) {
        List<String> out = new java.util.ArrayList<>();
        String remaining = text;
        while (remaining.length() > max) {
            // Prefer breaking at a paragraph (blank line), then a newline, then a space.
            int cut = remaining.lastIndexOf("\n\n", max);
            if (cut < max / 2) cut = remaining.lastIndexOf('\n', max);
            if (cut < max / 2) cut = remaining.lastIndexOf(' ', max);
            if (cut <= 0) cut = max; // hard cut; no whitespace in this window
            out.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut).stripLeading();
        }
        if (!remaining.isEmpty()) out.add(remaining);
        return out;
    }
}
