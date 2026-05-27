package com.tradingbot.analysis;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

/**
 * Wraps a BarSeries and exposes computed indicator values at the latest bar.
 */
public class IndicatorEngine {

    private final BarSeries series;
    private final int lastIndex;

    private final ClosePriceIndicator close;
    private final HighPriceIndicator high;
    private final LowPriceIndicator low;
    private final EMAIndicator ema9;
    private final EMAIndicator ema21;
    private final EMAIndicator ema50;
    private final RSIIndicator rsi14;
    private final ATRIndicator atr14;
    private final VolumeIndicator volume;

    // Momentum
    private final MACDIndicator macd;
    private final EMAIndicator macdSignal;
    private final StochasticOscillatorKIndicator stochK;
    private final StochasticOscillatorDIndicator stochD;

    // Volatility / bands
    private final BollingerBandsMiddleIndicator bbMiddle;
    private final BollingerBandsUpperIndicator bbUpper;
    private final BollingerBandsLowerIndicator bbLower;

    // Trend strength
    private final ADXIndicator adx;
    private final PlusDIIndicator plusDI;
    private final MinusDIIndicator minusDI;

    // Volume
    private final OnBalanceVolumeIndicator obv;
    private final VWAPIndicator vwap;

    public IndicatorEngine(BarSeries series) {
        this.series = series;
        this.lastIndex = series.getEndIndex();

        this.close  = new ClosePriceIndicator(series);
        this.high   = new HighPriceIndicator(series);
        this.low    = new LowPriceIndicator(series);
        this.ema9   = new EMAIndicator(close, 9);
        this.ema21  = new EMAIndicator(close, 21);
        this.ema50  = new EMAIndicator(close, 50);
        this.rsi14  = new RSIIndicator(close, 14);
        this.atr14  = new ATRIndicator(series, 14);
        this.volume = new VolumeIndicator(series);

        this.macd       = new MACDIndicator(close, 12, 26);
        this.macdSignal = new EMAIndicator(macd, 9);
        this.stochK     = new StochasticOscillatorKIndicator(series, 14);
        this.stochD     = new StochasticOscillatorDIndicator(stochK);

        SMAIndicator sma20 = new SMAIndicator(close, 20);
        StandardDeviationIndicator sd20 = new StandardDeviationIndicator(close, 20);
        this.bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        this.bbUpper  = new BollingerBandsUpperIndicator(bbMiddle, sd20, DecimalNum.valueOf(2));
        this.bbLower  = new BollingerBandsLowerIndicator(bbMiddle, sd20, DecimalNum.valueOf(2));

        this.adx     = new ADXIndicator(series, 14, 14);
        this.plusDI  = new PlusDIIndicator(series, 14);
        this.minusDI = new MinusDIIndicator(series, 14);

        this.obv  = new OnBalanceVolumeIndicator(series);
        this.vwap = new VWAPIndicator(series, 14);
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

    // Per-index accessors used by ChartRenderer to build full series arrays
    public double ema9AtIndex(int i)  { return ema9.getValue(i).doubleValue(); }
    public double ema21AtIndex(int i) { return ema21.getValue(i).doubleValue(); }
    public double ema50AtIndex(int i) { return ema50.getValue(i).doubleValue(); }

    // ── Momentum ──────────────────────────────────────────────────────────

    public double macd()          { return macd.getValue(lastIndex).doubleValue(); }
    public double macdSignal()    { return macdSignal.getValue(lastIndex).doubleValue(); }
    public double macdHistogram() { return macd() - macdSignal(); }

    /** True when the MACD line crossed above its signal line in the last lookback bars. */
    public boolean macdBullishCross(int lookback) {
        for (int i = Math.max(1, lastIndex - lookback); i <= lastIndex; i++) {
            boolean nowAbove  = macd.getValue(i).isGreaterThan(macdSignal.getValue(i));
            boolean prevBelow = macd.getValue(i - 1).isLessThanOrEqual(macdSignal.getValue(i - 1));
            if (nowAbove && prevBelow) return true;
        }
        return false;
    }

    public boolean macdBearishCross(int lookback) {
        for (int i = Math.max(1, lastIndex - lookback); i <= lastIndex; i++) {
            boolean nowBelow  = macd.getValue(i).isLessThan(macdSignal.getValue(i));
            boolean prevAbove = macd.getValue(i - 1).isGreaterThanOrEqual(macdSignal.getValue(i - 1));
            if (nowBelow && prevAbove) return true;
        }
        return false;
    }

    public double stochK() { return stochK.getValue(lastIndex).doubleValue(); }
    public double stochD() { return stochD.getValue(lastIndex).doubleValue(); }

    // ── Volatility / Bollinger ────────────────────────────────────────────

    public double bbUpper()  { return bbUpper.getValue(lastIndex).doubleValue(); }
    public double bbMiddle() { return bbMiddle.getValue(lastIndex).doubleValue(); }
    public double bbLower()  { return bbLower.getValue(lastIndex).doubleValue(); }

    /** Bandwidth = (upper - lower) / middle. Low values indicate a squeeze. */
    public double bbBandwidth() {
        double mid = bbMiddle();
        return mid != 0 ? (bbUpper() - bbLower()) / mid : 0;
    }

    /** %B = (price - lower) / (upper - lower). 0 = at lower band, 1 = at upper band. */
    public double bbPercentB() {
        double span = bbUpper() - bbLower();
        return span != 0 ? (currentPrice() - bbLower()) / span : 0.5;
    }

    /** True when the current bandwidth is in the lowest quartile of the last `lookback` bars. */
    public boolean isBollingerSqueeze(int lookback) {
        int start = Math.max(20, lastIndex - lookback);
        if (start >= lastIndex) return false;
        double current = bbBandwidth();
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        for (int i = start; i <= lastIndex; i++) {
            double mid = bbMiddle.getValue(i).doubleValue();
            double bw = mid != 0
                    ? (bbUpper.getValue(i).doubleValue() - bbLower.getValue(i).doubleValue()) / mid : 0;
            if (bw < min) min = bw;
            if (bw > max) max = bw;
        }
        if (max == min) return false;
        return current <= min + (max - min) * 0.25;
    }

    // ── Trend strength (ADX / DMI) ────────────────────────────────────────

    public double adx()     { return adx.getValue(lastIndex).doubleValue(); }
    public double plusDI()  { return plusDI.getValue(lastIndex).doubleValue(); }
    public double minusDI() { return minusDI.getValue(lastIndex).doubleValue(); }

    /** ADX > 25 is the conventional threshold for a trending (vs ranging) market. */
    public boolean isTrending() { return adx() > 25; }

    // ── Volume ────────────────────────────────────────────────────────────

    public double obv()  { return obv.getValue(lastIndex).doubleValue(); }
    public double vwap() { return vwap.getValue(lastIndex).doubleValue(); }

    /** Slope of OBV over the last N bars (rising = accumulation, falling = distribution). */
    public double obvSlope(int lookback) {
        int start = Math.max(0, lastIndex - lookback);
        return obv.getValue(lastIndex).doubleValue() - obv.getValue(start).doubleValue();
    }

    // ── Divergence ────────────────────────────────────────────────────────

    /**
     * Bullish RSI divergence: price prints a lower low over the lookback window while
     * RSI prints a higher low. Signals weakening downside momentum.
     */
    public boolean bullishRsiDivergence(int lookback) {
        int mid = lastIndex - lookback / 2;
        if (mid <= 0 || lookback < 6) return false;
        int recentLowIdx = lowestIndex(low, mid + 1, lastIndex);
        int priorLowIdx  = lowestIndex(low, Math.max(0, lastIndex - lookback), mid);
        if (recentLowIdx < 0 || priorLowIdx < 0) return false;
        boolean priceLowerLow = low.getValue(recentLowIdx).isLessThan(low.getValue(priorLowIdx));
        boolean rsiHigherLow   = rsi14.getValue(recentLowIdx).isGreaterThan(rsi14.getValue(priorLowIdx));
        return priceLowerLow && rsiHigherLow;
    }

    /**
     * Bearish RSI divergence: price prints a higher high while RSI prints a lower high.
     * Signals weakening upside momentum.
     */
    public boolean bearishRsiDivergence(int lookback) {
        int mid = lastIndex - lookback / 2;
        if (mid <= 0 || lookback < 6) return false;
        int recentHighIdx = highestIndex(high, mid + 1, lastIndex);
        int priorHighIdx  = highestIndex(high, Math.max(0, lastIndex - lookback), mid);
        if (recentHighIdx < 0 || priorHighIdx < 0) return false;
        boolean priceHigherHigh = high.getValue(recentHighIdx).isGreaterThan(high.getValue(priorHighIdx));
        boolean rsiLowerHigh    = rsi14.getValue(recentHighIdx).isLessThan(rsi14.getValue(priorHighIdx));
        return priceHigherHigh && rsiLowerHigh;
    }

    private int highestIndex(HighPriceIndicator ind, int from, int to) {
        int idx = -1;
        Num best = null;
        for (int i = from; i <= to; i++) {
            Num v = ind.getValue(i);
            if (best == null || v.isGreaterThan(best)) { best = v; idx = i; }
        }
        return idx;
    }

    private int lowestIndex(LowPriceIndicator ind, int from, int to) {
        int idx = -1;
        Num best = null;
        for (int i = from; i <= to; i++) {
            Num v = ind.getValue(i);
            if (best == null || v.isLessThan(best)) { best = v; idx = i; }
        }
        return idx;
    }

    // ── Market structure ──────────────────────────────────────────────────

    public enum Structure { UPTREND, DOWNTREND, RANGE }

    /**
     * Classifies recent market structure by comparing the most recent swing high/low
     * against the prior swing high/low: higher-high + higher-low = uptrend, etc.
     */
    public Structure marketStructure(int lookback) {
        int half = Math.max(3, lookback / 2);
        if (lastIndex < lookback) return Structure.RANGE;
        double recentHigh = swingHighBetween(lastIndex - half, lastIndex);
        double priorHigh  = swingHighBetween(lastIndex - lookback, lastIndex - half - 1);
        double recentLow  = swingLowBetween(lastIndex - half, lastIndex);
        double priorLow   = swingLowBetween(lastIndex - lookback, lastIndex - half - 1);

        boolean hh = recentHigh > priorHigh;
        boolean hl = recentLow > priorLow;
        boolean lh = recentHigh < priorHigh;
        boolean ll = recentLow < priorLow;

        if (hh && hl) return Structure.UPTREND;
        if (lh && ll) return Structure.DOWNTREND;
        return Structure.RANGE;
    }

    private double swingHighBetween(int from, int to) {
        from = Math.max(0, from);
        double h = Double.MIN_VALUE;
        for (int i = from; i <= to; i++) h = Math.max(h, high.getValue(i).doubleValue());
        return h;
    }

    private double swingLowBetween(int from, int to) {
        from = Math.max(0, from);
        double l = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) l = Math.min(l, low.getValue(i).doubleValue());
        return l;
    }
}
