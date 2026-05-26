package com.tradingbot.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tradingbot.model.TradeIdea;
import org.jdbi.v3.core.Jdbi;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Persists pending trade-idea menus keyed by Discord channel ID.
 * Replaces the in-memory ConcurrentHashMap in SessionStore.
 */
public class SessionDao {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type IDEAS_TYPE = new TypeToken<List<TradeIdea>>() {}.getType();

    private final Jdbi jdbi;

    public SessionDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void store(String channelId, List<TradeIdea> ideas) {
        String json = GSON.toJson(ideas);
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO session_setups(channel_id, ideas_json) VALUES(:cid, :json) " +
                "ON CONFLICT(channel_id) DO UPDATE SET ideas_json = excluded.ideas_json, " +
                "saved_at = strftime('%s','now')")
            .bind("cid", channelId)
            .bind("json", json)
            .execute());
    }

    public List<TradeIdea> get(String channelId) {
        return jdbi.withHandle(h ->
            h.createQuery("SELECT ideas_json FROM session_setups WHERE channel_id = :cid")
             .bind("cid", channelId)
             .mapTo(String.class)
             .findOne()
             .map(json -> (List<TradeIdea>) GSON.fromJson(json, IDEAS_TYPE))
             .orElse(null));
    }

    public void clear(String channelId) {
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM session_setups WHERE channel_id = :cid")
            .bind("cid", channelId)
            .execute());
    }
}
