package com.tradingbot.model;

public class TradeIdea {

    public enum Direction { LONG, SHORT }
    public enum Conviction { HIGH, MEDIUM, LOW }

    private final String ticker;
    private final String timeframe;
    private final Direction direction;
    private final double entry;
    private final double stopLoss;
    private final double target;
    private final double riskRewardRatio;
    private final Conviction conviction;
    private final String rationale;

    public TradeIdea(String ticker, String timeframe, Direction direction,
                     double entry, double stopLoss, double target, Conviction conviction, String rationale) {
        this.ticker = ticker;
        this.timeframe = timeframe;
        this.direction = direction;
        this.entry = entry;
        this.stopLoss = stopLoss;
        this.target = target;
        this.conviction = conviction;
        this.rationale = rationale;

        double risk = Math.abs(entry - stopLoss);
        double reward = Math.abs(target - entry);
        this.riskRewardRatio = risk > 0 ? reward / risk : 0;
    }

    public String formatForDiscord() {
        String emoji = direction == Direction.LONG ? "📈" : "📉";
        String convictionBadge = switch (conviction) {
            case HIGH -> "🔥 HIGH";
            case MEDIUM -> "⚡ MEDIUM";
            case LOW -> "💤 LOW";
        };

        return String.format("""
                %s **%s %s** | `%s` | %s
                > **Entry:** $%.2f
                > **Stop Loss:** $%.2f
                > **Target:** $%.2f
                > **R:R:** 1:%.1f
                > %s
                """,
                emoji, direction, ticker, timeframe, convictionBadge,
                entry, stopLoss, target, riskRewardRatio, rationale);
    }

    public String getTicker() { return ticker; }
    public String getTimeframe() { return timeframe; }
    public Direction getDirection() { return direction; }
    public double getEntry() { return entry; }
    public double getStopLoss() { return stopLoss; }
    public double getTarget() { return target; }
    public double getRiskRewardRatio() { return riskRewardRatio; }
    public Conviction getConviction() { return conviction; }
    public String getRationale() { return rationale; }
}
