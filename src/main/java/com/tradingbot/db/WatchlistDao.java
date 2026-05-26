package com.tradingbot.db;

import org.jdbi.v3.core.Jdbi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class WatchlistDao {

    private final Jdbi jdbi;

    public WatchlistDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void add(String ticker, boolean autoPlay) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO watchlist(ticker, auto_play) VALUES(:ticker, :auto) " +
                "ON CONFLICT(ticker) DO UPDATE SET auto_play = excluded.auto_play")
            .bind("ticker", ticker.toUpperCase())
            .bind("auto", autoPlay ? 1 : 0)
            .execute());
    }

    public void remove(String ticker) {
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM watchlist WHERE ticker = :ticker")
            .bind("ticker", ticker.toUpperCase())
            .execute());
    }

    /** Returns ordered map: ticker → autoPlay. */
    public Map<String, Boolean> loadAll() {
        return jdbi.withHandle(h ->
            h.createQuery("SELECT ticker, auto_play FROM watchlist ORDER BY added_at")
             .map((rs, ctx) -> Map.entry(rs.getString("ticker"), rs.getInt("auto_play") == 1))
             .stream()
             .collect(Collectors.toMap(
                 Map.Entry::getKey,
                 Map.Entry::getValue,
                 (a, b) -> b,
                 LinkedHashMap::new)));
    }

    public boolean isAutoPlay(String ticker) {
        return jdbi.withHandle(h ->
            h.createQuery("SELECT auto_play FROM watchlist WHERE ticker = :ticker")
             .bind("ticker", ticker.toUpperCase())
             .mapTo(Integer.class)
             .findOne()
             .map(v -> v == 1)
             .orElse(false));
    }

    public boolean isEmpty() {
        return jdbi.withHandle(h ->
            h.createQuery("SELECT COUNT(*) FROM watchlist")
             .mapTo(Integer.class)
             .one()) == 0;
    }

    public void clear() {
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM watchlist").execute());
    }
}
