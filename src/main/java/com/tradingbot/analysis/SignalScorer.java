package com.tradingbot.analysis;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.alpaca.AlpacaClient.Timeframe;
import com.tradingbot.model.TradeIdea;
import com.tradingbot.model.TradeIdea.Conviction;
import com.tradingbot.model.TradeIdea.Direction;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Map;

/**
 * Fuses multi-timeframe technical signals with an (optional) fundamental score into a single
 * 0-100 composite conviction for each {@link TradeIdea}. This is the decision engine: it converts
 * the raw indicator picture into one number that gates display ordering and auto-placement.
 *
 * <p>All inputs are computed locally from already-fetched OHLCV bars — no extra API calls.
 */
public class SignalScorer {

    private static final int MIN_BARS = 30;

    /** Higher timeframes carry more weight in the multi-timeframe alignment score. */
    private static final Map<Timeframe, Double> TF_WEIGHT = Map.of(
            Timeframe.M5,  0.5,
            Timeframe.M15, 0.8,
            Timeframe.H1,  1.2,
            Timeframe.H4,  1.6,
            Timeframe.D1,  2.0);

    /**
     * Scores every idea in place. {@code fundamentalScore} is on a -100..100 scale
     * (positive = bullish fundamentals); pass {@link Double#NaN} when unavailable (e.g. crypto).
     */
    public void scoreAll(List<TradeIdea> ideas,
                         Map<Timeframe, BarSeries> seriesMap,
                         double fundamentalScore) {
        if (ideas == null) return;
        for (TradeIdea idea : ideas) {
            try {
                score(idea, seriesMap, fundamentalScore);
            } catch (Exception ignored) {
                // A scoring failure must never drop a trade idea; it simply stays unscored.
            }
        }
    }

    public void score(TradeIdea idea, Map<Timeframe, BarSeries> seriesMap, double fundamentalScore) {
        Timeframe tf = parseTimeframe(idea.getTimeframe());
        BarSeries series = tf != null ? seriesMap.get(tf) : null;
        if (series == null || series.getBarCount() < MIN_BARS) return;

        IndicatorEngine eng = new IndicatorEngine(series);
        double techScore  = technicalScore(idea.getDirection(), eng);
        double mtfScore   = mtfAlignment(idea.getDirection(), seriesMap);
        String regime     = regime(eng);

        boolean haveFund = !Double.isNaN(fundamentalScore);
        // Normalize fundamental (-100..100) to 0..100 aligned with the trade direction.
        double fundNorm = haveFund ? (fundamentalScore + 100.0) / 2.0 : Double.NaN;
        double fundContribution = !haveFund ? 0
                : (idea.getDirection() == Direction.LONG ? fundNorm : 100.0 - fundNorm);

        // Fundamentals matter for swing horizons (H4/D1), not intraday scalps.
        boolean swingTf = tf == Timeframe.H4 || tf == Timeframe.D1;
        double wTech, wMtf, wFund;
        if (haveFund && swingTf) {
            wTech = 0.50; wMtf = 0.20; wFund = 0.30;
        } else {
            wTech = 0.65; wMtf = 0.35; wFund = 0.0;
        }

        double composite = wTech * techScore + wMtf * mtfScore + wFund * fundContribution;
        composite = Math.max(0, Math.min(100, composite));

        int stars = stars(composite);
        Conviction conv = convictionFor(composite);
        idea.applyScoring(composite, techScore,
                haveFund ? fundamentalScore : Double.NaN, mtfScore, regime, stars, conv);
    }

    /** Directional technical score 0-100: how strongly the indicators support this direction now. */
    private double technicalScore(Direction dir, IndicatorEngine eng) {
        boolean isLong = dir == Direction.LONG;

        // Trend (0..1): EMA stack alignment + DI dominance scaled by ADX strength.
        double trend = 0;
        if (isLong && eng.isBullishEmaStack()) trend += 0.5;
        if (!isLong && eng.isBearishEmaStack()) trend += 0.5;
        boolean diAligned = isLong ? eng.plusDI() > eng.minusDI() : eng.minusDI() > eng.plusDI();
        if (diAligned) trend += 0.5 * Math.min(1.0, eng.adx() / 40.0);

        // Momentum (0..1): MACD histogram sign + RSI healthy zone + stochastic position.
        double momentum = 0;
        double hist = eng.macdHistogram();
        if ((isLong && hist > 0) || (!isLong && hist < 0)) momentum += 0.4;
        double rsi = eng.rsi();
        if (isLong  && rsi > 45 && rsi < 70) momentum += 0.3;
        if (!isLong && rsi < 55 && rsi > 30) momentum += 0.3;
        double stoch = eng.stochK();
        if (isLong  && stoch > eng.stochD()) momentum += 0.3;
        if (!isLong && stoch < eng.stochD()) momentum += 0.3;

        // Volume (0..1): OBV slope aligned + above-average current volume.
        double volume = 0;
        double obvSlope = eng.obvSlope(10);
        if ((isLong && obvSlope > 0) || (!isLong && obvSlope < 0)) volume += 0.6;
        if (eng.avgVolume20() > 0 && eng.currentVolume() > eng.avgVolume20()) volume += 0.4;

        // Structure (0..1): market structure agrees with direction.
        double structure = 0;
        IndicatorEngine.Structure s = eng.marketStructure(20);
        if (isLong  && s == IndicatorEngine.Structure.UPTREND)   structure = 1.0;
        if (!isLong && s == IndicatorEngine.Structure.DOWNTREND) structure = 1.0;
        if (s == IndicatorEngine.Structure.RANGE)                structure = 0.4;

        // Divergence (0..1): momentum divergence in the trade's favor.
        double divergence = 0;
        if (isLong  && eng.bullishRsiDivergence(20)) divergence = 1.0;
        if (!isLong && eng.bearishRsiDivergence(20)) divergence = 1.0;

        double score = 0.30 * trend + 0.25 * momentum + 0.15 * volume
                     + 0.15 * structure + 0.15 * divergence;
        return Math.max(0, Math.min(100, score * 100));
    }

    /** 0-100: weighted fraction of timeframes whose trend bias agrees with the direction. */
    private double mtfAlignment(Direction dir, Map<Timeframe, BarSeries> seriesMap) {
        boolean isLong = dir == Direction.LONG;
        double agree = 0, total = 0;
        for (var e : seriesMap.entrySet()) {
            BarSeries series = e.getValue();
            if (series == null || series.getBarCount() < MIN_BARS) continue;
            double w = TF_WEIGHT.getOrDefault(e.getKey(), 1.0);
            total += w;
            IndicatorEngine eng = new IndicatorEngine(series);
            boolean bull = eng.isBullishEmaStack()
                    || eng.marketStructure(20) == IndicatorEngine.Structure.UPTREND;
            boolean bear = eng.isBearishEmaStack()
                    || eng.marketStructure(20) == IndicatorEngine.Structure.DOWNTREND;
            if (isLong && bull) agree += w;
            if (!isLong && bear) agree += w;
        }
        return total > 0 ? (agree / total) * 100 : 50;
    }

    private String regime(IndicatorEngine eng) {
        double atrPct = eng.currentPrice() != 0 ? eng.atr() / eng.currentPrice() * 100 : 0;
        if (atrPct > 4.0) return "VOLATILE";
        if (eng.isTrending()) return "TRENDING";
        return "RANGING";
    }

    private int stars(double composite) {
        if (composite >= 85) return 5;
        if (composite >= 72) return 4;
        if (composite >= 58) return 3;
        if (composite >= 45) return 2;
        return 1;
    }

    private Conviction convictionFor(double composite) {
        if (composite >= 65) return Conviction.HIGH;   // auto-trade gate (aggressive)
        if (composite >= 50) return Conviction.MEDIUM;
        return Conviction.LOW;
    }

    private Timeframe parseTimeframe(String displayName) {
        if (displayName == null) return null;
        for (Timeframe tf : AlpacaClient.Timeframe.values()) {
            if (tf.displayName.equalsIgnoreCase(displayName)) return tf;
        }
        return null;
    }
}
