package com.tradingbot.backtest;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.TradeIdeaGenerator;
import com.tradingbot.model.TradeIdea;
import com.tradingbot.model.TradeIdea.Direction;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

/**
 * Event-driven, no-look-ahead backtest of the rules engine over a historical bar series.
 *
 * <p>At each bar it rebuilds a sub-series containing only the bars up to that point, asks
 * {@link TradeIdeaGenerator} for setups, simulates the resulting trade forward bar-by-bar
 * (stop-before-target on ambiguous bars, the conservative assumption), and aggregates
 * win rate, expectancy in R, profit factor and max drawdown. Single position at a time.
 */
public class Backtester {

    private static final int WARMUP_BARS = 50;

    private final TradeIdeaGenerator rulesEngine = new TradeIdeaGenerator();

    public record Result(
            String ticker, String timeframe,
            int signals, int trades, int wins, int losses,
            double winRate, double avgR, double expectancy,
            double profitFactor, double maxDrawdownR, double totalR) {

        public String formatForDiscord() {
            if (trades == 0) {
                return String.format("📉 **Backtest %s %s** — no completed trades over the available history.",
                        ticker, timeframe);
            }
            return String.format("""
                    📊 **Backtest: %s %s**
                    > Signals: %d | Completed trades: %d
                    > Win rate: %.1f%% (%d W / %d L)
                    > Avg R / trade: %.2f | Expectancy: %.2fR
                    > Profit factor: %.2f
                    > Max drawdown: %.2fR | Total: %.2fR
                    _No look-ahead; conservative stop-before-target fills. Past performance ≠ future results._
                    """,
                    ticker, timeframe, signals, trades,
                    winRate * 100, wins, losses,
                    avgR, expectancy, profitFactor, maxDrawdownR, totalR);
        }
    }

    public Result run(String ticker, AlpacaClient.Timeframe tf, BarSeries series) {
        int last = series.getEndIndex();
        List<Double> rMultiples = new ArrayList<>();
        int signals = 0;

        int i = Math.max(WARMUP_BARS, series.getBeginIndex() + WARMUP_BARS);
        while (i < last) {
            BarSeries sub = series.getSubSeries(series.getBeginIndex(), i + 1);
            List<TradeIdea> ideas = rulesEngine.generate(ticker, tf, sub);
            if (ideas.isEmpty()) { i++; continue; }

            TradeIdea idea = ideas.get(0); // strongest candidate from the rules engine
            signals++;
            int exitIndex = simulate(series, i, idea, rMultiples);
            i = exitIndex > i ? exitIndex + 1 : i + 1;
        }

        return aggregate(ticker, tf.displayName, signals, rMultiples);
    }

    /**
     * Simulates a single trade entered at the close of {@code entryIndex}. Appends the realized R
     * multiple to {@code out} when the trade resolves and returns the exit bar index, or
     * {@code entryIndex} if the trade never resolved before the data ended.
     */
    private int simulate(BarSeries series, int entryIndex, TradeIdea idea, List<Double> out) {
        boolean isLong = idea.getDirection() == Direction.LONG;
        double entry = idea.getEntry();
        double stop  = idea.getStopLoss();
        double target = idea.getTarget();
        double riskR = idea.getRiskRewardRatio(); // R won if target hit

        for (int j = entryIndex + 1; j <= series.getEndIndex(); j++) {
            double hi = series.getBar(j).getHighPrice().doubleValue();
            double lo = series.getBar(j).getLowPrice().doubleValue();

            if (isLong) {
                boolean stopHit   = lo <= stop;
                boolean targetHit = hi >= target;
                if (stopHit) { out.add(-1.0); return j; }      // conservative: stop first
                if (targetHit) { out.add(riskR); return j; }
            } else {
                boolean stopHit   = hi >= stop;
                boolean targetHit = lo <= target;
                if (stopHit) { out.add(-1.0); return j; }
                if (targetHit) { out.add(riskR); return j; }
            }
        }
        return entryIndex; // unresolved — ignore (no R recorded)
    }

    private Result aggregate(String ticker, String tfName, int signals, List<Double> rs) {
        int trades = rs.size();
        if (trades == 0) {
            return new Result(ticker, tfName, signals, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        int wins = 0, losses = 0;
        double sumR = 0, grossWin = 0, grossLoss = 0;
        for (double r : rs) {
            sumR += r;
            if (r > 0) { wins++; grossWin += r; }
            else       { losses++; grossLoss += Math.abs(r); }
        }
        double winRate = (double) wins / trades;
        double avgR = sumR / trades;
        double profitFactor = grossLoss > 0 ? grossWin / grossLoss : (grossWin > 0 ? Double.POSITIVE_INFINITY : 0);

        // Max drawdown on the cumulative-R equity curve.
        double peak = 0, equity = 0, maxDd = 0;
        for (double r : rs) {
            equity += r;
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }

        return new Result(ticker, tfName, signals, trades, wins, losses,
                winRate, avgR, avgR, profitFactor, maxDd, sumR);
    }
}
