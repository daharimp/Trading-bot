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
    private Conviction conviction;
    private final String rationale;

    // ── Composite scoring (populated by SignalScorer; -1 / null = not yet scored) ──
    private double compositeScore  = -1;
    private double technicalScore  = -1;
    private double fundamentalScore = Double.NaN;
    private double mtfAlignment    = -1;
    private String regime          = null;
    private int    stars           = 0;
    private int    suggestedQty    = 0;

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

    /**
     * Attaches composite-score breakdown produced by {@link com.tradingbot.analysis.SignalScorer}.
     * The composite-derived conviction overrides the original rules/LLM conviction so that
     * downstream placement gates act on the fused score.
     */
    public void applyScoring(double compositeScore, double technicalScore, double fundamentalScore,
                             double mtfAlignment, String regime, int stars, Conviction scoredConviction) {
        this.compositeScore   = compositeScore;
        this.technicalScore   = technicalScore;
        this.fundamentalScore = fundamentalScore;
        this.mtfAlignment     = mtfAlignment;
        this.regime           = regime;
        this.stars            = stars;
        this.conviction       = scoredConviction;
    }

    public boolean isScored()            { return compositeScore >= 0; }
    public double getCompositeScore()    { return compositeScore; }
    public double getTechnicalScore()    { return technicalScore; }
    public double getFundamentalScore()  { return fundamentalScore; }
    public double getMtfAlignment()      { return mtfAlignment; }
    public String getRegime()            { return regime; }
    public int    getStars()             { return stars; }
    public void   setSuggestedQty(int q) { this.suggestedQty = q; }
    public int    getSuggestedQty()      { return suggestedQty; }

    public String formatForDiscord() {
        String emoji = direction == Direction.LONG ? "📈" : "📉";
        String convictionBadge = switch (conviction) {
            case HIGH -> "🔥 HIGH";
            case MEDIUM -> "⚡ MEDIUM";
            case LOW -> "💤 LOW";
        };

        StringBuilder scoreLine = new StringBuilder();
        if (isScored()) {
            String starStr = "⭐".repeat(Math.max(0, Math.min(5, stars)));
            scoreLine.append(String.format("> **Score:** %.0f/100 %s", compositeScore, starStr));
            if (regime != null) scoreLine.append(" | ").append(regime);
            scoreLine.append(String.format("  _(tech %.0f", technicalScore));
            if (!Double.isNaN(fundamentalScore)) scoreLine.append(String.format(", fund %.0f", fundamentalScore));
            scoreLine.append(String.format(", MTF %.0f)_\n", mtfAlignment));
            if (suggestedQty > 0) scoreLine.append(String.format("> **Suggested qty:** %d (risk-sized)\n", suggestedQty));
        }

        return String.format("""
                %s **%s %s** | `%s` | %s
                > **Entry:** $%.2f
                > **Stop Loss:** $%.2f
                > **Target:** $%.2f
                > **R:R:** 1:%.1f
                %s> %s
                """,
                emoji, direction, ticker, timeframe, convictionBadge,
                entry, stopLoss, target, riskRewardRatio, scoreLine, rationale);
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
