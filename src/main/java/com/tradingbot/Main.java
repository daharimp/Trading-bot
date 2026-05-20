package com.tradingbot;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.FundamentalAnalyst;
import com.tradingbot.analysis.TechnicalAnalyst;
import com.tradingbot.discord.DiscordListener;
import com.tradingbot.fundamental.FundamentalDataClient;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Dotenv env = Dotenv.configure().ignoreIfMissing().load();

        String discordToken  = requireEnv(env, "DISCORD_BOT_TOKEN");
        String alpacaKey     = requireEnv(env, "ALPACA_API_KEY");
        String alpacaSecret  = requireEnv(env, "ALPACA_API_SECRET");
        String openAiKey     = requireEnv(env, "OPENAI_API_KEY");
        String alpacaMode    = env.get("ALPACA_MODE", "paper");
        String channelName   = env.get("DISCORD_CHANNEL_NAME", "");

        String alphaVantageKey  = requireEnv(env, "ALPHA_VANTAGE_API_KEY");
        String openRouterKey    = requireEnv(env, "OPENROUTER_API_KEY");
        String fundamentalModel = env.get("FUNDAMENTAL_LLM_MODEL", "google/gemini-flash-1.5");

        AlpacaClient alpaca              = new AlpacaClient(alpacaKey, alpacaSecret, alpacaMode);
        FundamentalDataClient fdClient   = new FundamentalDataClient(alphaVantageKey);
        TechnicalAnalyst techAnalyst     = new TechnicalAnalyst(openAiKey);
        FundamentalAnalyst fundAnalyst   = new FundamentalAnalyst(openRouterKey, fundamentalModel);
        DiscordListener listener         = new DiscordListener(alpaca, fdClient, techAnalyst, fundAnalyst, channelName);

        log.info("Starting Trading Bot (Alpaca: {} | Technical: GPT-4o | Fundamental: {})", alpacaMode, fundamentalModel);

        GatewayDiscordClient gateway = DiscordClient.create(discordToken)
                .login()
                .block();

        if (gateway == null) {
            log.error("Failed to connect to Discord. Check your DISCORD_BOT_TOKEN.");
            System.exit(1);
        }

        gateway.on(MessageCreateEvent.class, listener::handle).subscribe();

        log.info("Bot online. Send a ticker (e.g. AAPL) in #{}", channelName);
        gateway.onDisconnect().block();
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
