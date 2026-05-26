package com.tradingbot.db;

import org.jdbi.v3.core.Jdbi;

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

    /**
     * Records an Alpaca bracket order.
     * tif: "day", "opg", or "gtc"
     */
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
     * entryTxid: the entry+stop-loss conditional order txid.
     * tpTxid: the separate take-profit limit txid.
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
}
