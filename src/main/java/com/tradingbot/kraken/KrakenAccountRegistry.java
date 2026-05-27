package com.tradingbot.kraken;

import com.tradingbot.db.BrokerAccountDao;
import okhttp3.OkHttpClient;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a KrakenClient for a Discord user.
 * Falls back to the bot-level default client (from .env) if the user has no personal account registered.
 * Instances are cached in-memory; the cache is invalidated when accounts are added, removed, or switched.
 */
public class KrakenAccountRegistry {

    private final BrokerAccountDao dao;
    private final OkHttpClient http;
    private final KrakenClient defaultClient;

    // Cache: discordUserId -> KrakenClient built from their active account
    private final ConcurrentHashMap<String, KrakenClient> cache = new ConcurrentHashMap<>();

    public KrakenAccountRegistry(BrokerAccountDao dao, OkHttpClient http, KrakenClient defaultClient) {
        this.dao           = dao;
        this.http          = http;
        this.defaultClient = defaultClient;
    }

    /**
     * Returns the KrakenClient for the given Discord user.
     * Uses a cached instance when available; builds from DB otherwise.
     * Falls back to the global default client if no personal account is registered.
     */
    public KrakenClient resolve(String discordUserId) {
        return cache.computeIfAbsent(discordUserId, uid -> {
            Optional<BrokerAccountDao.BrokerAccount> acct = dao.getActive(uid, "KRAKEN");
            if (acct.isEmpty()) return defaultClient;
            BrokerAccountDao.BrokerAccount a = acct.get();
            return new KrakenClient(http, a.apiKey(), a.apiSecret());
        });
    }

    /** Adds or replaces a labeled account and evicts the cache entry so the next call rebuilds. */
    public void register(String discordUserId, String label, String apiKey, String apiSecret) {
        dao.upsert(discordUserId, "KRAKEN", label, apiKey, apiSecret);
        cache.remove(discordUserId);
    }

    /** Removes a labeled account and evicts the cache. */
    public boolean remove(String discordUserId, String label) {
        boolean removed = dao.remove(discordUserId, "KRAKEN", label);
        cache.remove(discordUserId);
        return removed;
    }

    /** Switches the active account for a user and evicts the cache. */
    public boolean setActive(String discordUserId, String label) {
        boolean ok = dao.setActive(discordUserId, "KRAKEN", label);
        cache.remove(discordUserId);
        return ok;
    }

    /** Returns the list of registered account labels for display (no secrets). */
    public java.util.List<String> listLabels(String discordUserId) {
        return dao.listLabels(discordUserId, "KRAKEN");
    }
}
