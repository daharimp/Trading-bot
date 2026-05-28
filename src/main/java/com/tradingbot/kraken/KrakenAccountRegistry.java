package com.tradingbot.kraken;

import com.tradingbot.db.BrokerAccountDao;
import okhttp3.OkHttpClient;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a KrakenClient for a Discord user.
 *
 * Returns the user's personal client if they've registered one. Otherwise returns the
 * bot-owner default client ONLY if their Discord user ID is on the owner allowlist;
 * for anyone else the result is empty and callers must refuse the command. This prevents
 * unregistered Discord members from operating against the bot owner's default credentials.
 *
 * Instances are cached in-memory; the cache is invalidated when accounts are added, removed, or switched.
 */
public class KrakenAccountRegistry {

    private final BrokerAccountDao dao;
    private final OkHttpClient http;
    private final KrakenClient defaultClient;
    private final Set<String> ownerIds;

    // Cache: discordUserId -> KrakenClient built from their active account
    private final ConcurrentHashMap<String, KrakenClient> cache = new ConcurrentHashMap<>();

    public KrakenAccountRegistry(BrokerAccountDao dao, OkHttpClient http,
                                 KrakenClient defaultClient, Set<String> ownerIds) {
        this.dao           = dao;
        this.http          = http;
        this.defaultClient = defaultClient;
        this.ownerIds      = ownerIds == null ? Set.of() : Set.copyOf(ownerIds);
    }

    /**
     * Returns the KrakenClient for the given Discord user, or empty if they have no
     * personal account registered and are not on the bot-owner allowlist.
     */
    public Optional<KrakenClient> resolve(String discordUserId) {
        KrakenClient cached = cache.get(discordUserId);
        if (cached != null) return Optional.of(cached);

        Optional<BrokerAccountDao.BrokerAccount> acct = dao.getActive(discordUserId, "KRAKEN");
        if (acct.isPresent()) {
            BrokerAccountDao.BrokerAccount a = acct.get();
            KrakenClient personal = new KrakenClient(http, a.apiKey(), a.apiSecret());
            cache.put(discordUserId, personal);
            return Optional.of(personal);
        }

        if (ownerIds.contains(discordUserId)) {
            cache.put(discordUserId, defaultClient);
            return Optional.of(defaultClient);
        }

        return Optional.empty();
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
