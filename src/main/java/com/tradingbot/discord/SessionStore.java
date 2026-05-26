package com.tradingbot.discord;

import com.tradingbot.db.SessionDao;
import com.tradingbot.model.TradeIdea;

import java.util.List;

public class SessionStore {

    private final SessionDao dao;

    public SessionStore(SessionDao dao) {
        this.dao = dao;
    }

    public void store(String channelId, List<TradeIdea> ideas) {
        dao.store(channelId, ideas);
    }

    public List<TradeIdea> get(String channelId) {
        return dao.get(channelId);
    }

    public void clear(String channelId) {
        dao.clear(channelId);
    }
}
