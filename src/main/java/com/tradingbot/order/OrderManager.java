package com.tradingbot.order;

import com.tradingbot.alpaca.AlpacaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class OrderManager {

    private static final Logger log = LoggerFactory.getLogger(OrderManager.class);

    private final AlpacaClient alpaca;

    public OrderManager(AlpacaClient alpaca) {
        this.alpaca = alpaca;
    }

    /**
     * Validates inputs and places a bracket order.
     * direction: "LONG" or "SHORT"
     * Returns a Discord-formatted confirmation string.
     */
    public String placePlay(String ticker, String direction, double entry,
                             double stop, double target, int qty) throws IOException {
        String side = direction.equalsIgnoreCase("LONG") ? "buy" : "sell";

        // Basic sanity checks before touching the API
        if (qty <= 0) return "❌ Quantity must be greater than 0.";
        if (entry <= 0 || stop <= 0 || target <= 0)
            return "❌ Entry, stop, and target must all be positive values.";

        if (side.equals("buy")) {
            if (stop >= entry)  return "❌ For a LONG trade, stop must be *below* entry.";
            if (target <= entry) return "❌ For a LONG trade, target must be *above* entry.";
        } else {
            if (stop <= entry)  return "❌ For a SHORT trade, stop must be *above* entry.";
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

        String orderId = alpaca.placeOrder(ticker, side, entry, stop, target, qty);

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
