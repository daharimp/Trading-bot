package com.tradingbot.db;

import org.jdbi.v3.core.Jdbi;

import java.util.Optional;

public class BrokerAccountDao {

    private final Jdbi jdbi;

    public BrokerAccountDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public record BrokerAccount(String discordUserId, String broker, String label,
                                 String apiKey, String apiSecret) {}

    /** Adds or replaces a labeled account for a user. */
    public void upsert(String discordUserId, String broker, String label,
                       String apiKey, String apiSecret) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO broker_accounts(discord_user_id, broker, label, api_key, api_secret) " +
                "VALUES(:uid, :broker, :label, :key, :secret) " +
                "ON CONFLICT(discord_user_id, broker, label) DO UPDATE SET " +
                "api_key = excluded.api_key, api_secret = excluded.api_secret")
            .bind("uid", discordUserId)
            .bind("broker", broker.toUpperCase())
            .bind("label", label)
            .bind("key", apiKey)
            .bind("secret", apiSecret)
            .execute());
    }

    /** Removes a labeled account for a user. */
    public boolean remove(String discordUserId, String broker, String label) {
        int rows = jdbi.withHandle(h -> h.createUpdate(
                "DELETE FROM broker_accounts WHERE discord_user_id = :uid " +
                "AND broker = :broker AND label = :label")
            .bind("uid", discordUserId)
            .bind("broker", broker.toUpperCase())
            .bind("label", label)
            .execute());
        return rows > 0;
    }

    /** Sets a specific label as the active account, clearing the flag on all other labels. */
    public boolean setActive(String discordUserId, String broker, String label) {
        return jdbi.withHandle(h -> {
            int rows = h.createUpdate(
                    "UPDATE broker_accounts SET active = (label = :label) " +
                    "WHERE discord_user_id = :uid AND broker = :broker")
                .bind("uid", discordUserId)
                .bind("broker", broker.toUpperCase())
                .bind("label", label)
                .execute();
            return rows > 0;
        });
    }

    /** Returns the currently active account for a user+broker, or empty if none registered. */
    public Optional<BrokerAccount> getActive(String discordUserId, String broker) {
        return jdbi.withHandle(h ->
            h.createQuery(
                    "SELECT discord_user_id, broker, label, api_key, api_secret " +
                    "FROM broker_accounts WHERE discord_user_id = :uid " +
                    "AND broker = :broker AND active = 1 LIMIT 1")
             .bind("uid", discordUserId)
             .bind("broker", broker.toUpperCase())
             .map((rs, ctx) -> new BrokerAccount(
                     rs.getString("discord_user_id"),
                     rs.getString("broker"),
                     rs.getString("label"),
                     rs.getString("api_key"),
                     rs.getString("api_secret")))
             .findOne());
    }

    /** Returns all registered accounts for a user (labels only, no secrets). */
    public java.util.List<String> listLabels(String discordUserId, String broker) {
        return jdbi.withHandle(h ->
            h.createQuery(
                    "SELECT label, active FROM broker_accounts " +
                    "WHERE discord_user_id = :uid AND broker = :broker ORDER BY label")
             .bind("uid", discordUserId)
             .bind("broker", broker.toUpperCase())
             .map((rs, ctx) -> rs.getString("label") + (rs.getInt("active") == 1 ? " (active)" : ""))
             .list());
    }
}
