package com.tradingbot;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.AnalysisService;
import com.tradingbot.analysis.FundamentalAnalyst;
import com.tradingbot.analysis.TechnicalAnalyst;
import com.tradingbot.chart.ChartRenderer;
import com.tradingbot.discord.DiscordListener;
import com.tradingbot.discord.DiscordNotifier;
import com.tradingbot.discord.SessionStore;
import com.tradingbot.fundamental.FundamentalDataClient;
import com.tradingbot.order.OrderManager;
import com.tradingbot.scheduler.WatchlistScheduler;
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
        String technicalModel   = env.get("TECHNICAL_LLM_MODEL", "claude-sonnet-4-6");
        String scheduleTime     = env.get("ANALYSIS_SCHEDULE_TIME", "23:00");
        int defaultQty          = Integer.parseInt(env.get("DEFAULT_QTY", "1"));

        AlpacaClient alpaca           = new AlpacaClient(alpacaKey, alpacaSecret, alpacaMode);
        FundamentalDataClient fdClient = new FundamentalDataClient(alphaVantageKey);
        TechnicalAnalyst techAnalyst  = new TechnicalAnalyst(anthropicKey, technicalModel);
        FundamentalAnalyst fundAnalyst = new FundamentalAnalyst(openRouterKey, fundamentalModel);
        AnalysisService analysisService = new AnalysisService(alpaca, techAnalyst, fundAnalyst, fdClient);
        OrderManager orderManager     = new OrderManager(alpaca);
        ChartRenderer chartRenderer   = new ChartRenderer();

        log.info("Starting Trading Bot (Alpaca: {} | Technical: {} | Fundamental: {} | Schedule: {}ET)",
                alpacaMode, technicalModel, fundamentalModel, scheduleTime);

        GatewayDiscordClient gateway = DiscordClient.create(discordToken)
                .gateway()
                .setEnabledIntents(IntentSet.of(
                        Intent.GUILDS,
                        Intent.GUILD_MESSAGES,
                        Intent.MESSAGE_CONTENT))
                .login()
                .block();

        if (gateway == null) {
            log.error("Failed to connect to Discord. Check your DISCORD_BOT_TOKEN.");
            System.exit(1);
        }

        DiscordNotifier notifier = new DiscordNotifier(gateway, channelName);
        SessionStore sessionStore = new SessionStore();
        WatchlistScheduler scheduler = new WatchlistScheduler(
                notifier, analysisService, orderManager, scheduleTime, defaultQty);
        scheduler.start();

        DiscordListener listener = new DiscordListener(
                analysisService, orderManager, scheduler, chartRenderer, sessionStore, channelName);

        gateway.on(MessageCreateEvent.class, listener::handle).subscribe();

        log.info("Bot online in #{} | Commands: !analyze !watch !play !positions !help", channelName);
        gateway.onDisconnect().block();

        scheduler.shutdown();
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
