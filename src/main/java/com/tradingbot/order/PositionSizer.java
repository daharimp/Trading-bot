package com.tradingbot.order;

import com.tradingbot.model.TradeIdea;
import com.tradingbot.model.TradeIdea.Conviction;

/**
 * Risk-based position sizing. Instead of a fixed share/contract count, size each trade so that the
 * loss taken if the stop is hit equals a fixed fraction of account equity, scaled by conviction.
 *
 * <p>qty = floor( (equity * riskPerTradePct/100 * convictionFactor) / |entry - stop| )
 *
 * <p>This is compute-only and broker-agnostic; the caller supplies current account equity.
 */
public class PositionSizer {

    private final double riskPerTradePct;     // e.g. 1.0 = risk 1% of equity per trade
    private final double maxPortfolioRiskPct; // cap on summed open risk across positions

    public PositionSizer(double riskPerTradePct, double maxPortfolioRiskPct) {
        this.riskPerTradePct = riskPerTradePct;
        this.maxPortfolioRiskPct = maxPortfolioRiskPct;
    }

    public double getMaxPortfolioRiskPct() { return maxPortfolioRiskPct; }

    /** HIGH conviction risks the full per-trade budget; MEDIUM half; LOW a quarter. */
    private static double convictionFactor(Conviction c) {
        return switch (c) {
            case HIGH   -> 1.0;
            case MEDIUM -> 0.5;
            case LOW    -> 0.25;
        };
    }

    /**
     * Computes the risk-sized quantity for a trade idea given current account equity.
     * Returns 0 when inputs are invalid (zero equity, zero stop distance, etc.).
     */
    public int sizeFor(TradeIdea idea, double equity) {
        return sizeFor(equity, idea.getEntry(), idea.getStopLoss(), idea.getConviction());
    }

    public int sizeFor(double equity, double entry, double stop, Conviction conviction) {
        if (equity <= 0 || entry <= 0 || stop <= 0) return 0;
        double stopDistance = Math.abs(entry - stop);
        if (stopDistance <= 0) return 0;

        double riskBudget = equity * (riskPerTradePct / 100.0) * convictionFactor(conviction);
        double qty = riskBudget / stopDistance;
        return (int) Math.floor(Math.max(0, qty));
    }

    /** Dollar risk that a given qty would expose if the stop is hit. */
    public double riskDollars(double entry, double stop, int qty) {
        return Math.abs(entry - stop) * qty;
    }
}
