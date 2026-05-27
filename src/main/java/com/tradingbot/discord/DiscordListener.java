package com.tradingbot.discord;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.AnalysisService;
import com.tradingbot.chart.ChartRenderer;
import com.tradingbot.kraken.KrakenAccountRegistry;
import com.tradingbot.kraken.KrakenClient;
import com.tradingbot.model.TradeIdea;
import com.tradingbot.monitor.AccountMonitor;
import com.tradingbot.order.OrderManager;
import com.tradingbot.scheduler.WatchlistScheduler;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.PrivateChannel;
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
    private final KrakenAccountRegistry krakenRegistry;
    private AccountMonitor accountMonitor;

    public DiscordListener(AnalysisService analysisService, OrderManager orderManager,
                           WatchlistScheduler scheduler, ChartRenderer chartRenderer,
                           SessionStore sessionStore, String watchedChannel,
                           KrakenAccountRegistry krakenRegistry) {
        this.analysisService  = analysisService;
        this.orderManager     = orderManager;
        this.scheduler        = scheduler;
        this.chartRenderer    = chartRenderer;
        this.sessionStore     = sessionStore;
        this.watchedChannel   = watchedChannel;
        this.krakenRegistry   = krakenRegistry;
    }

    public void setAccountMonitor(AccountMonitor monitor) { this.accountMonitor = monitor; }

    public Mono<Void> handle(MessageCreateEvent event) {
        Message message = event.getMessage();
        if (message.getAuthor().map(u -> u.isBot()).orElse(true)) return Mono.empty();

        String content  = message.getContent().trim().toUpperCase();
        String authorId = message.getAuthor().map(u -> u.getId().asString()).orElse("");

        // !account commands are handled in DMs only, before channel filtering
        if (content.startsWith("!ACCOUNT")) {
            return message.getChannel()
                    .flatMap(ch -> routeAccount(content, ch, authorId, message))
                    .then();
        }

        return message.getChannel()
                .filter(ch -> channelMatches(ch, watchedChannel))
                .flatMap(ch -> route(content, ch, authorId))
                .then();
    }

    private Mono<?> routeAccount(String content, MessageChannel ch, String authorId, Message message) {
        // !ACCOUNT ADD KRAKEN <label> <api_key> <api_secret>
        // !ACCOUNT USE KRAKEN <label>
        // !ACCOUNT REMOVE KRAKEN <label>
        // !ACCOUNT LIST KRAKEN
        // Must be a DM for add/remove to protect credentials
        String[] parts = content.split("\\s+");
        boolean isDm = ch instanceof PrivateChannel;

        // Parse subcommand from uppercased content, but use raw message for credentials to avoid case corruption
        String sub = parts.length > 1 ? parts[1] : "";

        if (sub.isEmpty()) return ch.createMessage(accountHelpText());

        return switch (sub) {
            case "ADD" -> {
                if (!isDm) yield ch.createMessage("⚠️ Use a DM for `!account add` to keep your credentials private.");
                // Parse from raw message — content is uppercased and would corrupt the secret
                String[] rawParts = message.getContent().trim().split("\\s+");
                if (rawParts.length < 6) yield ch.createMessage("Usage: `!account add kraken <label> <api_key> <api_secret>`");
                String label  = rawParts[3].toLowerCase();
                String apiKey = rawParts[4];
                String secret = rawParts[5];
                krakenRegistry.register(authorId, label, apiKey, secret);
                // Delete the message immediately so the secret doesn't linger in chat history
                message.delete("removing credential message").subscribe();
                yield ch.createMessage("✅ Kraken account **" + label + "** registered (your message was auto-deleted). Use `!account use kraken " + label + "` to activate it.");
            }
            case "USE" -> {
                if (parts.length < 4) yield ch.createMessage("Usage: `!account use kraken <label>`");
                String label = parts[3].toLowerCase();
                boolean ok = krakenRegistry.setActive(authorId, label);
                yield ch.createMessage(ok
                        ? "✅ Switched active Kraken account to **" + label + "**."
                        : "❌ No account with label **" + label + "** found. Use `!account list kraken` to see registered accounts.");
            }
            case "REMOVE" -> {
                if (!isDm) yield ch.createMessage("⚠️ Use a DM for `!account remove`.");
                if (parts.length < 4) yield ch.createMessage("Usage: `!account remove kraken <label>`");
                String label = parts[3].toLowerCase();
                boolean ok = krakenRegistry.remove(authorId, label);
                yield ch.createMessage(ok
                        ? "✅ Removed Kraken account **" + label + "**."
                        : "❌ No account with label **" + label + "** found.");
            }
            case "LIST" -> {
                java.util.List<String> labels = krakenRegistry.listLabels(authorId);
                yield ch.createMessage(labels.isEmpty()
                        ? "No Kraken accounts registered. Use `!account add kraken <label> <key> <secret>` in a DM."
                        : "🔑 **Your Kraken accounts:** " + String.join(", ", labels));
            }
            default -> ch.createMessage(accountHelpText());
        };
    }

    private Mono<?> route(String content, MessageChannel ch, String authorId) {

        KrakenClient kc = krakenRegistry.resolve(authorId);

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

        // ── !BACKTEST TICKER [timeframe] ────────────────────────────────────────
        if (content.startsWith("!BACKTEST ")) {
            String[] parts = content.substring("!BACKTEST ".length()).trim().split("\\s+");
            String ticker = parts[0];
            String tf = parts.length > 1 ? parts[1] : null;
            if (!ticker.matches("[A-Z0-9/]{1,10}")) {
                return ch.createMessage("❌ Usage: `!backtest BTC 1H` (timeframes: 5m 15m 1H 4H 1D)");
            }
            return ch.createMessage("⏳ Backtesting **" + ticker + "** on historical bars...")
                    .flatMap(status -> Mono.fromCallable(() -> analysisService.runBacktest(ticker, tf))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(ch::createMessage))
                    .onErrorResume(e -> ch.createMessage("❌ Backtest failed: " + e.getMessage()));
        }

        // ── !PLAY (manual bracket order) ────────────────────────────────────────
        if (content.startsWith("!PLAY ")) {
            return Mono.fromCallable(() -> parseAndPlaceOrder(content))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ch::createMessage)
                    .onErrorResume(e -> ch.createMessage("❌ Order failed: " + e.getMessage()));
        }

        // ── !POSITIONS ──────────────────────────────────────────────────────────
        if (content.startsWith("!POSITIONS")) {
            return sendChunked(ch, buildPositionsOutput(kc), null);
        }

        // ── !EXIT ───────────────────────────────────────────────────────────────
        if (content.startsWith("!EXIT")) {
            String result = handleExit(content.trim(), kc);
            return ch.createMessage(result);
        }

        // ── !RESUME ─────────────────────────────────────────────────────────────
        if (content.startsWith("!RESUME")) {
            return ch.createMessage(handleResume());
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

    private String handleExit(String content, KrakenClient kc) {
        String[] parts = content.split("\\s+");
        if (parts.length < 2) return "Usage: !exit <SYMBOL> or !exit all";
        String arg = parts[1].toUpperCase();
        if (kc == null) return "Kraken not configured.";
        if (arg.equals("ALL")) {
            Map<String, Double> positions = kc.getOpenPositions();
            if (positions.isEmpty()) return "No open positions found.";
            StringBuilder sb = new StringBuilder("Closing all positions:\n");
            for (String ticker : positions.keySet()) {
                sb.append(String.format("• %s: %s\n", ticker, kc.closePosition(ticker)));
            }
            return sb.toString().trim();
        }
        return "Exited " + arg + " — " + kc.closePosition(arg);
    }

    private String handleResume() {
        if (accountMonitor == null) return "AccountMonitor not configured.";
        return accountMonitor.unpause();
    }

    private String buildPositionsOutput(KrakenClient kc) {
        StringBuilder sb = new StringBuilder();
        if (kc != null) {
            Map<String, Double> positions = kc.getOpenPositions();
            if (!positions.isEmpty()) {
                sb.append("📊 **Live Crypto Positions (Kraken)**\n");
                double totalCryptoValue = 0;
                for (Map.Entry<String, Double> pos : positions.entrySet()) {
                    String ticker = pos.getKey(); double qty = pos.getValue();
                    try {
                        double mid = kc.getLatestQuote(ticker).mid();
                        totalCryptoValue += qty * mid;
                        sb.append(String.format("%-6s | %.6f | Now: $%.4f | Value: $%.2f\n", ticker, qty, mid, qty * mid));
                    } catch (Exception e) {
                        sb.append(String.format("%-6s | %.6f (price unavailable)\n", ticker, qty));
                    }
                }
                if (accountMonitor != null) {
                    Map<String, Double> balances = kc.getAccountBalance();
                    double usdCash = balances.getOrDefault("ZUSD", 0.0);
                    double total = accountMonitor.getTotalUsdValue();
                    sb.append(String.format("Balance: $%.2f USD + ~$%.2f crypto = ~$%.2f total\n", usdCash, totalCryptoValue, total));
                }
            } else {
                sb.append("📊 **Kraken:** No open crypto positions.\n");
            }
            sb.append("\n");
        }
        sb.append(String.format("Trades today: %d/%d\n\n", orderManager.getDailyTradeCount(), orderManager.getMaxDailyTrades()));
        sb.append("📋 **Paper Positions (Alpaca)**\n");
        try {
            sb.append(orderManager.listPositions());
        } catch (Exception e) {
            sb.append("❌ Could not fetch Alpaca positions: ").append(e.getMessage());
        }
        return sb.toString();
    }

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

        TradeIdea chosen = pending.get(n - 1);
        if (qty <= 0) {
            qty = chosen.getSuggestedQty();  // fall back to the risk-sized quantity
        }
        if (qty <= 0) {
            return "❌ No risk-sized quantity available — specify one. Example: `!pick 1 qty=10`";
        }

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

    private String accountHelpText() {
        return """
                **Account Commands** *(use in DM for add/remove)*
                `!account add kraken <label> <api_key> <api_secret>` — Register a Kraken account
                `!account use kraken <label>` — Switch active Kraken account
                `!account remove kraken <label>` — Remove a registered account
                `!account list kraken` — List your registered accounts
                """;
    }

    private String helpText() {
        return """
                **Trading Bot Commands**
                `!analyze AAPL` — Full technical + fundamental analysis with chart
                `!analyze BTC/USD` — Crypto analysis (no fundamentals)
                `!pick 1` — Place setup #1 using the risk-sized qty (or `!pick 1 qty=10` to override)
                `!backtest BTC 1H` — Backtest the rules engine on historical bars (timeframes: 5m 15m 1H 4H 1D)
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
