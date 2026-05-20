package com.tradingbot.analysis;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.model.TradeIdea;
import com.tradingbot.model.TradeIdea.Conviction;
import com.tradingbot.model.TradeIdea.Direction;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

public class TradeIdeaGenerator {

    private static final int MIN_BARS = 30;

    /**
     * Analyzes a BarSeries for a given timeframe and returns trade ideas.
     * Returns an empty list if there is insufficient data or no clear setup.
     */
    public List<TradeIdea> generate(String ticker, AlpacaClient.Timeframe timeframe, BarSeries series) {
        List<TradeIdea> ideas = new ArrayList<>();

        if (series.getBarCount() < MIN_BARS) {
            return ideas;
        }

        IndicatorEngine eng = new IndicatorEngine(series);
        double price = eng.currentPrice();
        double atr   = eng.atr();
        double rsi   = eng.rsi();

        // --- Setup 1: EMA trend continuation ---
        if (eng.isBullishEmaStack() && rsi < 65) {
            double entry    = price;
            double stopLoss = eng.ema21() - (atr * 0.5);
            double target   = entry + (entry - stopLoss) * 2.0;
            Conviction conviction = eng.recentBullishCross(5) ? Conviction.HIGH : Conviction.MEDIUM;
            String rationale = String.format(
                    "Bull EMA stack (9>21>50). RSI %.0f — not overbought. " +
                    "Stop below EMA21 (%.2f). Target 2R.", rsi, eng.ema21());
            ideas.add(new TradeIdea(ticker, timeframe.displayName, Direction.LONG,
                    entry, stopLoss, target, conviction, rationale));
        }

        if (eng.isBearishEmaStack() && rsi > 35) {
            double entry    = price;
            double stopLoss = eng.ema21() + (atr * 0.5);
            double target   = entry - (stopLoss - entry) * 2.0;
            Conviction conviction = eng.recentBearishCross(5) ? Conviction.HIGH : Conviction.MEDIUM;
            String rationale = String.format(
                    "Bear EMA stack (9<21<50). RSI %.0f — not oversold. " +
                    "Stop above EMA21 (%.2f). Target 2R.", rsi, eng.ema21());
            ideas.add(new TradeIdea(ticker, timeframe.displayName, Direction.SHORT,
                    entry, stopLoss, target, conviction, rationale));
        }

        // --- Setup 2: RSI oversold bounce (long) ---
        if (rsi < 32 && price > eng.ema50()) {
            double entry    = price;
            double stopLoss = eng.swingLow(10) - (atr * 0.25);
            double target   = entry + (entry - stopLoss) * 2.0;
            String rationale = String.format(
                    "RSI oversold (%.0f) while price holds above EMA50 (%.2f). " +
                    "Bounce setup targeting 2R.", rsi, eng.ema50());
            ideas.add(new TradeIdea(ticker, timeframe.displayName, Direction.LONG,
                    entry, stopLoss, target, Conviction.MEDIUM, rationale));
        }

        // --- Setup 3: RSI overbought fade (short) ---
        if (rsi > 68 && price < eng.ema50()) {
            double entry    = price;
            double stopLoss = eng.swingHigh(10) + (atr * 0.25);
            double target   = entry - (stopLoss - entry) * 2.0;
            String rationale = String.format(
                    "RSI overbought (%.0f) while price below EMA50 (%.2f). " +
                    "Fade setup targeting 2R.", rsi, eng.ema50());
            ideas.add(new TradeIdea(ticker, timeframe.displayName, Direction.SHORT,
                    entry, stopLoss, target, Conviction.MEDIUM, rationale));
        }

        // --- Setup 4: EMA9/21 fresh cross with volume confirmation ---
        boolean highVol = eng.currentVolume() > eng.avgVolume20() * 1.3;

        if (eng.recentBullishCross(3) && highVol) {
            double entry    = price;
            double stopLoss = eng.swingLow(5) - (atr * 0.1);
            double target   = entry + (entry - stopLoss) * 2.5;
            String rationale = String.format(
                    "Fresh EMA9/21 bullish cross with %.0f%% above-average volume. " +
                    "Momentum breakout. Target 2.5R.", (eng.currentVolume() / eng.avgVolume20() - 1) * 100);
            ideas.add(new TradeIdea(ticker, timeframe.displayName, Direction.LONG,
                    entry, stopLoss, target, Conviction.HIGH, rationale));
        }

        if (eng.recentBearishCross(3) && highVol) {
            double entry    = price;
            double stopLoss = eng.swingHigh(5) + (atr * 0.1);
            double target   = entry - (stopLoss - entry) * 2.5;
            String rationale = String.format(
                    "Fresh EMA9/21 bearish cross with %.0f%% above-average volume. " +
                    "Momentum breakdown. Target 2.5R.", (eng.currentVolume() / eng.avgVolume20() - 1) * 100);
            ideas.add(new TradeIdea(ticker, timeframe.displayName, Direction.SHORT,
                    entry, stopLoss, target, Conviction.HIGH, rationale));
        }

        // Filter out setups with R:R below 1.5
        ideas.removeIf(idea -> idea.getRiskRewardRatio() < 1.5);

        return ideas;
    }
}
