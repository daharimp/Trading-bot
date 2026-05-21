package com.tradingbot.discord;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.spec.MessageCreateSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;

public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    private final GatewayDiscordClient gateway;
    private final String channelName;

    public DiscordNotifier(GatewayDiscordClient gateway, String channelName) {
        this.gateway     = gateway;
        this.channelName = channelName;
    }

    public void postMessage(String content) {
        resolveChannel()
                .flatMap(ch -> ch.createMessage(content))
                .doOnError(e -> log.error("Failed to post message: {}", e.getMessage()))
                .subscribe();
    }

    public void postFile(String content, byte[] pngBytes, String filename) {
        resolveChannel()
                .flatMap(ch -> ch.createMessage(MessageCreateSpec.builder()
                        .content(content)
                        .addFile(filename, new ByteArrayInputStream(pngBytes))
                        .build()))
                .doOnError(e -> log.error("Failed to post file: {}", e.getMessage()))
                .subscribe();
    }

    private Mono<MessageChannel> resolveChannel() {
        if (channelName == null || channelName.isBlank()) {
            log.warn("No DISCORD_CHANNEL_NAME set — cannot post proactive message");
            return Mono.empty();
        }
        return gateway.getGuilds()
                .flatMap(guild -> guild.getChannels())
                .filter(ch -> ch.getName().equalsIgnoreCase(channelName))
                .ofType(MessageChannel.class)
                .next();
    }
}
