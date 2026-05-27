package com.tradingbot.db;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Persists placed orders (both Alpaca and Kraken) to the orders table.
 * order_id = Alpaca UUID or Kraken entry txid.
 * tp_order_id = Kraken TP txid (null for Alpaca bracket).
 */
public class OrderDao {

    private final Jdbi jdbi;

    public OrderDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Records an Alpaca bracket order. tif: "day", "opg", or "gtc" */
    public long recordAlpacaOrder(String ticker, String direction, double entry,
                                   double stop, double target, int qty,
                                   String orderId, String tif) {
        return jdbi.withHandle(h ->
            h.createUpdate("""
                INSERT INTO orders(ticker, broker, order_id, direction,
                                   entry_price, stop_loss, target, qty, tif)
                VALUES(:ticker, 'ALPACA', :orderId, :dir,
                       :entry, :stop, :target, :qty, :tif)
                """)
             .bind("ticker",  ticker.toUpperCase())
             .bind("orderId", orderId)
             .bind("dir",     direction.toUpperCase())
             .bind("entry",   entry)
             .bind("stop",    stop)
             .bind("target",  target)
             .bind("qty",     qty)
             .bind("tif",     tif)
             .executeAndReturnGeneratedKeys("id")
             .mapTo(Long.class)
             .one());
    }

    /**
     * Records a Kraken two-order bracket.
     * entryTxid: entry+stop-loss conditional order txid.
     * tpTxid: separate take-profit limit txid.
     */
    public long recordKrakenOrder(String ticker, String direction, double entry,
                                   double stop, double target, int qty,
                                   String entryTxid, String tpTxid) {
        return jdbi.withHandle(h ->
            h.createUpdate("""
                INSERT INTO orders(ticker, broker, order_id, tp_order_id, direction,
                                   entry_price, stop_loss, target, qty, tif)
                VALUES(:ticker, 'KRAKEN', :entryTxid, :tpTxid, :dir,
                       :entry, :stop, :target, :qty, 'gtc')
                """)
             .bind("ticker",    ticker.toUpperCase())
             .bind("entryTxid", entryTxid)
             .bind("tpTxid",    tpTxid)
             .bind("dir",       direction.toUpperCase())
             .bind("entry",     entry)
             .bind("stop",      stop)
             .bind("target",    target)
             .bind("qty",       qty)
             .executeAndReturnGeneratedKeys("id")
             .mapTo(Long.class)
             .one());
    }

    /** Updates an order's status (open → filled / cancelled / partial). */
    public void updateStatus(String orderId, String status) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE orders SET status = :status WHERE order_id = :orderId")
            .bind("status",  status)
            .bind("orderId", orderId)
            .execute());
    }

    /**
     * Reconciles all ALPACA orders still marked 'open' in the DB against a live
     * status map fetched from Alpaca. Called once at boot to catch fills/cancels
     * that happened while the bot was offline.
     * Returns the number of rows updated.
     */
    public int reconcileOnBoot(Map<String, String> alpacaStatuses) {
        Map<String, String> normalize = Map.of(
                "filled",           "filled",
                "partially_filled", "partial",
                "canceled",         "cancelled",
                "expired",          "cancelled",
                "rejected",         "cancelled",
                "replaced",         "cancelled"
        );

        java.util.List<String> openIds = jdbi.withHandle(h ->
            h.createQuery("SELECT order_id FROM orders WHERE broker = 'ALPACA' AND status = 'open'")
             .mapTo(String.class)
             .list());

        int updated = 0;
        for (String orderId : openIds) {
            String raw = alpacaStatuses.get(orderId);
            if (raw == null) continue;
            String normalized = normalize.getOrDefault(raw, raw);
            if (!normalized.equals("open")) {
                updateStatus(orderId, normalized);
                log.info("reconcile: {} → {}", orderId.substring(0, 8), normalized);
                updated++;
            }
        }
        return updated;
    }

    private static final Logger log = LoggerFactory.getLogger(OrderDao.class);
}
