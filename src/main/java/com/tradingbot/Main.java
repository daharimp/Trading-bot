package com.tradingbot;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.AnalysisService;
import com.tradingbot.analysis.FundamentalAnalyst;
import com.tradingbot.analysis.TechnicalAnalyst;
import com.tradingbot.chart.ChartRenderer;
import com.tradingbot.db.BrokerAccountDao;
import com.tradingbot.db.DatabaseManager;
import com.tradingbot.db.OrderDao;
import com.tradingbot.db.SessionDao;
import com.tradingbot.db.WatchlistDao;
import com.tradingbot.discord.DiscordListener;
import com.tradingbot.discord.DiscordNotifier;
import com.tradingbot.discord.SessionStore;
import com.tradingbot.fundamental.FundamentalDataClient;
import com.tradingbot.kraken.KrakenAccountRegistry;
import com.tradingbot.kraken.KrakenClient;
import com.tradingbot.monitor.AccountMonitor;
import com.tradingbot.order.OrderManager;
import com.tradingbot.order.SlippageTracker;
import com.tradingbot.scheduler.WatchlistScheduler;
import okhttp3.OkHttpClient;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Dotenv env = Dotenv.configure().ignoreIfMissing().load();

        String discordToken     = requireEnv(env, "DISCORD_BOT_TOKEN");
        String alpacaKey        = requireEnv(env, "ALPACA_API_KEY");
        String alpacaSecret     = requireEnv(env, "ALPACA_API_SECRET");
        String anthropicKey     = requireEnv(env, "ANTHROPIC_API_KEY");
        String alphaVantageKey  = requireEnv(env, "ALPHA_VANTAGE_API_KEY");
        String openRouterKey    = requireEnv(env, "OPENROUTER_API_KEY");

        String alpacaMode       = env.get("ALPACA_MODE", "paper");
        String channelName      = env.get("DISCORD_CHANNEL_NAME", "");
        String fundamentalModel = env.get("FUNDAMENTAL_LLM_MODEL", "tencent/hunyuan-3-preview");
        String technicalModel   = env.get("TECHNICAL_LLM_MODEL", "mistralai/mistral-small-3.2-24b-instruct");
        String scheduleTime     = env.get("ANALYSIS_SCHEDULE_TIME", "23:00");
        int defaultQty          = Integer.parseInt(env.get("DEFAULT_QTY", "1"));
        String krakenKey        = env.get("KRAKEN_API_KEY", "");
        String krakenSecret     = env.get("KRAKEN_API_SECRET", "");
        String dbPath           = env.get("DB_PATH", "./trading-bot.db");
        // Comma-separated Discord user IDs allowed to use the default (bot-owner) Kraken creds
        // when they have no personal account registered. Other users get an empty resolve()
        // result and the command refuses. See KrakenAccountRegistry.
        java.util.Set<String> botOwnerIds = java.util.Arrays.stream(
                env.get("BOT_OWNER_DISCORD_IDS", "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        int maxDailyTrades      = Integer.parseInt(env.get("MAX_DAILY_TRADES", "15"));
        double minEquityUsd     = Double.parseDouble(env.get("MIN_EQUITY_USD", "500"));
        double riskPerTradePct  = Double.parseDouble(env.get("RISK_PER_TRADE_PCT", "1.0"));
        double maxPortfolioRisk = Double.parseDouble(env.get("MAX_PORTFOLIO_RISK_PCT", "5.0"));

        DatabaseManager db        = new DatabaseManager(dbPath);
        WatchlistDao watchlistDao = new WatchlistDao(db.jdbi());
        SessionDao   sessionDao   = new SessionDao(db.jdbi());
        OrderDao     orderDao     = new OrderDao(db.jdbi());
        BrokerAccountDao brokerAccountDao = new BrokerAccountDao(db.jdbi());
        com.tradingbot.db.PerformanceDao performanceDao = new com.tradingbot.db.PerformanceDao(db.jdbi());

        OkHttpClient httpClient       = new OkHttpClient();
        AlpacaClient alpaca           = new AlpacaClient(alpacaKey, alpacaSecret, alpacaMode);
        KrakenClient kraken           = new KrakenClient(httpClient, krakenKey, krakenSecret);
        KrakenAccountRegistry krakenRegistry = new KrakenAccountRegistry(brokerAccountDao, httpClient, kraken, botOwnerIds);
        alpaca.setKrakenClient(kraken);
        FundamentalDataClient fdClient = new FundamentalDataClient(alphaVantageKey);
        TechnicalAnalyst techAnalyst  = new TechnicalAnalyst(anthropicKey, openRouterKey, technicalModel);
        techAnalyst.setPerformanceContext(performanceDao::performanceContext);
        FundamentalAnalyst fundAnalyst = new FundamentalAnalyst(openRouterKey, fundamentalModel);
        AnalysisService analysisService = new AnalysisService(alpaca, techAnalyst, fundAnalyst, fdClient);
        analysisService.setKrakenClient(kraken);
        SlippageTracker slippageTracker = alpaca.newSlippageTracker();
        slippageTracker.start();
        OrderManager orderManager     = new OrderManager(alpaca, slippageTracker, orderDao, maxDailyTrades);
        orderManager.setKrakenClient(kraken);

        AccountMonitor accountMonitor = new AccountMonitor(kraken, minEquityUsd);
        orderManager.setAccountMonitor(accountMonitor);

        com.tradingbot.order.PositionSizer positionSizer =
                new com.tradingbot.order.PositionSizer(riskPerTradePct, maxPortfolioRisk);
        analysisService.setRiskSizing(positionSizer, accountMonitor::getTotalUsdValue);

        com.tradingbot.order.OutcomeTracker outcomeTracker =
                new com.tradingbot.order.OutcomeTracker(alpaca, kraken, orderDao, performanceDao);
        outcomeTracker.start();

        ChartRenderer chartRenderer   = new ChartRenderer();

        log.info("Starting Trading Bot (Alpaca: {} | Technical: {} | Fundamental: {} | Schedule: {}ET)",
                alpacaMode, technicalModel, fundamentalModel, scheduleTime);

        GatewayDiscordClient gateway = DiscordClient.create(discordToken)
                .gateway()
                .setEnabledIntents(IntentSet.of(
                        Intent.GUILDS,
                        Intent.GUILD_MESSAGES,
                        Intent.MESSAGE_CONTENT,
                        Intent.DIRECT_MESSAGES))
                .login()
                .block();

        if (gateway == null) {
            log.error("Failed to connect to Discord. Check your DISCORD_BOT_TOKEN.");
            System.exit(1);
        }

        DiscordNotifier notifier      = new DiscordNotifier(gateway, channelName);
        accountMonitor.setAlertCallback(notifier::postMessage);
        accountMonitor.start();
        SessionStore sessionStore     = new SessionStore(sessionDao);
        WatchlistScheduler scheduler  = new WatchlistScheduler(
                notifier, alpaca, analysisService, orderManager, watchlistDao, scheduleTime, defaultQty);
        scheduler.setKrakenClient(kraken);
        scheduler.start();

        DiscordListener listener = new DiscordListener(
                analysisService, orderManager, scheduler, chartRenderer, sessionStore, channelName, krakenRegistry);
        listener.setAccountMonitor(accountMonitor);

        gateway.on(MessageCreateEvent.class, listener::handle).subscribe();

        try {
            int fixed = orderDao.reconcileOnBoot(alpaca.getAllOrderStatuses());
            if (fixed > 0) log.info("Boot reconcile: updated {} stale order status(es)", fixed);
        } catch (Exception e) {
            log.warn("Boot reconcile failed (non-fatal): {}", e.getMessage());
        }

        log.info("Bot online in #{} | Commands: !analyze !watch !play !positions !help", channelName);
        gateway.onDisconnect().block();

        scheduler.shutdown();
        slippageTracker.shutdown();
        outcomeTracker.shutdown();
        db.close();
    }

    private static String requireEnv(Dotenv env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank() || value.startsWith("your_")) {
            System.err.println("Missing required environment variable: " + key);
            System.err.println("Copy .env.example to .env and fill in your credentials.");
            System.exit(1);
        }
        return value;
    }
}
