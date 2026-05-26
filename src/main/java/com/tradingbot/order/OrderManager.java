package com.tradingbot.order;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.kraken.KrakenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class OrderManager {

    private static final Logger log = LoggerFactory.getLogger(OrderManager.class);

    private final AlpacaClient alpaca;
    private final SlippageTracker slippageTracker;
    private KrakenClient kraken;

    public OrderManager(AlpacaClient alpaca, SlippageTracker slippageTracker) {
        this.alpaca = alpaca;
        this.slippageTracker = slippageTracker;
    }

    public void setKrakenClient(KrakenClient krakenClient) {
        this.kraken = krakenClient;
    }

    /**
     * Validates inputs and places a bracket order.
     * Crypto tickers route to Kraken (two-order bracket); stocks route to Alpaca.
     * direction: "LONG" or "SHORT"
     * Returns a Discord-formatted confirmation string.
     */
    public String placePlay(String ticker, String direction, double entry,
                             double stop, double target, int qty) throws Exception {
        String side = direction.equalsIgnoreCase("LONG") ? "buy" : "sell";

        if (qty <= 0) return "❌ Quantity must be greater than 0.";
        if (entry <= 0 || stop <= 0 || target <= 0)
            return "❌ Entry, stop, and target must all be positive values.";

        if (side.equals("buy")) {
            if (stop >= entry)   return "❌ For a LONG trade, stop must be *below* entry.";
            if (target <= entry) return "❌ For a LONG trade, target must be *above* entry.";
        } else {
            if (stop <= entry)   return "❌ For a SHORT trade, stop must be *above* entry.";
            if (target >= entry) return "❌ For a SHORT trade, target must be *below* entry.";
        }

        double risk   = Math.abs(entry - stop);
        double reward = Math.abs(target - entry);
        double rr     = reward / risk;

        if (rr < 1.0) {
            return String.format("❌ R:R is %.2f — too low. Minimum is 1.0. Adjust your levels.", rr);
        }

        log.info("Placing {} bracket order: {} {} @ {}, stop {}, target {}",
                direction, qty, ticker,
                String.format("%.2f", entry),
                String.format("%.2f", stop),
                String.format("%.2f", target));

        if (AlpacaClient.isCrypto(ticker)) {
            return placeCryptoPlay(ticker, direction, entry, stop, target, qty, rr);
        }

        String orderId = alpaca.placeOrder(ticker, side, entry, stop, target, qty);
        slippageTracker.record(orderId, ticker, side, entry);

        return String.format("""
                ✅ **Bracket Order Placed**
                • Ticker: **%s** | Direction: **%s**
                • Entry (limit): $%.2f
                • Stop loss:     $%.2f
                • Take profit:   $%.2f
                • Qty: %d shares | R:R %.2f
                • Order ID: `%s`

                Use `!cancel %s` to cancel before fill.
                """,
                ticker.toUpperCase(), direction.toUpperCase(),
                entry, stop, target, qty, rr,
                orderId, orderId);
    }

    /**
     * Places a bracket order using time_in_force=opg (limit-on-open).
     * Used by the overnight scheduler for the opening auction.
     * Crypto does not support OPG — falls back to a GTC Kraken bracket.
     */
    public String placePlayOpg(String ticker, String direction, double entry,
                                double stop, double target, int qty) throws Exception {
        String side = direction.equalsIgnoreCase("LONG") ? "buy" : "sell";

        if (qty <= 0) return "❌ Quantity must be greater than 0.";
        if (entry <= 0 || stop <= 0 || target <= 0)
            return "❌ Entry, stop, and target must all be positive values.";

        if (side.equals("buy")) {
            if (stop >= entry)   return "❌ For a LONG trade, stop must be *below* entry.";
            if (target <= entry) return "❌ For a LONG trade, target must be *above* entry.";
        } else {
            if (stop <= entry)   return "❌ For a SHORT trade, stop must be *above* entry.";
            if (target >= entry) return "❌ For a SHORT trade, target must be *below* entry.";
        }

        double risk   = Math.abs(entry - stop);
        double reward = Math.abs(target - entry);
        double rr     = reward / risk;
        if (rr < 1.0)
            return String.format("❌ R:R is %.2f — too low. Minimum is 1.0.", rr);

        log.info("Placing {} OPG bracket order: {} {} @ {}, stop {}, target {}",
                direction, qty, ticker,
                String.format("%.2f", entry),
                String.format("%.2f", stop),
                String.format("%.2f", target));

        if (AlpacaClient.isCrypto(ticker)) {
            return placeCryptoPlay(ticker, direction, entry, stop, target, qty, rr);
        }

        String orderId = alpaca.placeOrder(ticker, side, entry, stop, target, qty, "opg");
        slippageTracker.record(orderId, ticker, side, entry);

        return String.format("""
                ✅ **OPG Bracket Order Placed** *(opening auction)*
                • Ticker: **%s** | Direction: **%s**
                • Entry (limit-on-open): $%.2f
                • Stop loss:             $%.2f
                • Take profit:           $%.2f
                • Qty: %d shares | R:R %.2f
                • Order ID: `%s`

                Use `!cancel %s` to cancel before market open.
                """,
                ticker.toUpperCase(), direction.toUpperCase(),
                entry, stop, target, qty, rr, orderId, orderId);
    }

    /** Routes a crypto bracket order to Kraken and formats the Discord confirmation. */
    private String placeCryptoPlay(String ticker, String direction, double entry,
                                    double stop, double target, int qty, double rr) throws Exception {
        if (kraken == null) {
            return "❌ Kraken client not configured — cannot place crypto orders. Set `KRAKEN_API_KEY` and `KRAKEN_API_SECRET` in `.env`.";
        }

        String txids = kraken.placeOrder(ticker, direction, entry, stop, target, qty);
        String[] parts = txids.split("/", 2);
        String entryTxid = parts[0];
        String tpTxid    = parts.length > 1 ? parts[1] : "n/a";

        slippageTracker.record(entryTxid, ticker, direction.equalsIgnoreCase("LONG") ? "buy" : "sell", entry);

        return String.format("""
                ✅ **Kraken Bracket Placed** *(crypto — 24/7)*
                • Ticker: **%s** | Direction: **%s**
                • Entry (limit + stop-loss close): %.8f
                • Take profit (limit):             %.8f
                • Stop loss:                       %.8f
                • Qty: %d | R:R %.2f
                • Entry order: `%s`
                • TP order:    `%s`
                """,
                ticker.toUpperCase(), direction.toUpperCase(),
                entry, target, stop, qty, rr,
                entryTxid, tpTxid);
    }

    /** Returns formatted open positions for Discord. */
    public String listPositions() throws IOException {
        String positions = alpaca.getPositionsSummary();
        String orders    = alpaca.getOrdersSummary();
        return positions + "\n" + orders;
    }

    /** Cancels a pending order and returns a confirmation string. */
    public String cancelOrder(String orderId) throws IOException {
        alpaca.cancelOrder(orderId);
        return "✅ Order `" + orderId + "` cancelled.";
    }
}
