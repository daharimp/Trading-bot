package com.tradingbot.analysis;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.num.Num;

/**
 * Wraps a BarSeries and exposes computed indicator values at the latest bar.
 */
public class IndicatorEngine {

    private final BarSeries series;
    private final int lastIndex;

    private final ClosePriceIndicator close;
    private final EMAIndicator ema9;
    private final EMAIndicator ema21;
    private final EMAIndicator ema50;
    private final RSIIndicator rsi14;
    private final ATRIndicator atr14;
    private final VolumeIndicator volume;

    public IndicatorEngine(BarSeries series) {
        this.series = series;
        this.lastIndex = series.getEndIndex();

        this.close  = new ClosePriceIndicator(series);
        this.ema9   = new EMAIndicator(close, 9);
        this.ema21  = new EMAIndicator(close, 21);
        this.ema50  = new EMAIndicator(close, 50);
        this.rsi14  = new RSIIndicator(close, 14);
        this.atr14  = new ATRIndicator(series, 14);
        this.volume = new VolumeIndicator(series);
    }

    public double currentPrice() {
        return close.getValue(lastIndex).doubleValue();
    }

    public double ema9() {
        return ema9.getValue(lastIndex).doubleValue();
    }

    public double ema21() {
        return ema21.getValue(lastIndex).doubleValue();
    }

    public double ema50() {
        return ema50.getValue(lastIndex).doubleValue();
    }

    public double rsi() {
        return rsi14.getValue(lastIndex).doubleValue();
    }

    public double atr() {
        return atr14.getValue(lastIndex).doubleValue();
    }

    public double currentVolume() {
        return volume.getValue(lastIndex).doubleValue();
    }

    public double avgVolume20() {
        int start = Math.max(0, lastIndex - 19);
        double sum = 0;
        for (int i = start; i <= lastIndex; i++) {
            sum += volume.getValue(i).doubleValue();
        }
        return sum / (lastIndex - start + 1);
    }

    /** Recent swing high over the last N bars */
    public double swingHigh(int lookback) {
        double high = Double.MIN_VALUE;
        HighPriceIndicator highIndicator = new HighPriceIndicator(series);
        int start = Math.max(0, lastIndex - lookback);
        for (int i = start; i <= lastIndex; i++) {
            double h = highIndicator.getValue(i).doubleValue();
            if (h > high) high = h;
        }
        return high;
    }

    /** Recent swing low over the last N bars */
    public double swingLow(int lookback) {
        double low = Double.MAX_VALUE;
        LowPriceIndicator lowIndicator = new LowPriceIndicator(series);
        int start = Math.max(0, lastIndex - lookback);
        for (int i = start; i <= lastIndex; i++) {
            double l = lowIndicator.getValue(i).doubleValue();
            if (l < low) low = l;
        }
        return low;
    }

    /** True when EMA9 > EMA21 > EMA50 (bull stack) */
    public boolean isBullishEmaStack() {
        return ema9() > ema21() && ema21() > ema50();
    }

    /** True when EMA9 < EMA21 < EMA50 (bear stack) */
    public boolean isBearishEmaStack() {
        return ema9() < ema21() && ema21() < ema50();
    }

    /** True when EMA9 crossed above EMA21 in the last crossoverLookback bars */
    public boolean recentBullishCross(int crossoverLookback) {
        for (int i = Math.max(1, lastIndex - crossoverLookback); i <= lastIndex; i++) {
            boolean nowAbove  = ema9.getValue(i).isGreaterThan(ema21.getValue(i));
            boolean prevBelow = ema9.getValue(i - 1).isLessThan(ema21.getValue(i - 1));
            if (nowAbove && prevBelow) return true;
        }
        return false;
    }

    /** True when EMA9 crossed below EMA21 in the last crossoverLookback bars */
    public boolean recentBearishCross(int crossoverLookback) {
        for (int i = Math.max(1, lastIndex - crossoverLookback); i <= lastIndex; i++) {
            boolean nowBelow  = ema9.getValue(i).isLessThan(ema21.getValue(i));
            boolean prevAbove = ema9.getValue(i - 1).isGreaterThan(ema21.getValue(i - 1));
            if (nowBelow && prevAbove) return true;
        }
        return false;
    }

    public int barCount() {
        return series.getBarCount();
    }
}
