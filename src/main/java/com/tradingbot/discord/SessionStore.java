package com.tradingbot.discord;

import com.tradingbot.model.TradeIdea;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionStore {

    private final Map<String, List<TradeIdea>> pendingSetups = new ConcurrentHashMap<>();

    public void store(String channelId, List<TradeIdea> ideas) {
        pendingSetups.put(channelId, ideas);
    }

    public List<TradeIdea> get(String channelId) {
        return pendingSetups.get(channelId);
    }

    public void clear(String channelId) {
        pendingSetups.remove(channelId);
    }
}
