package com.tradingbot.db;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes trade outcome history. Backs the dormant {@code position_outcomes} table and
 * {@code setup_performance} view defined in V1__init.sql so realized win-rates can be fed back
 * into the LLM analysis prompts.
 */
public class PerformanceDao {

    private final Jdbi jdbi;

    public PerformanceDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Per (direction, conviction) realized performance from the setup_performance view. */
    public record SetupStat(String direction, String conviction, int total, int wins,
                            double winPct, double avgPnl) {}

    /** Records the realized outcome of a closed position. outcome: WIN | LOSS | BREAKEVEN. */
    public void recordOutcome(Long orderId, String ticker, String direction,
                              double entryPrice, double exitPrice, int qty,
                              double pnl, String outcome) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO position_outcomes(order_id, ticker, direction, entry_price,
                                              exit_price, qty, pnl, outcome)
                VALUES(:orderId, :ticker, :dir, :entry, :exit, :qty, :pnl, :outcome)
                """)
            .bind("orderId", orderId)
            .bind("ticker",  ticker.toUpperCase())
            .bind("dir",     direction.toUpperCase())
            .bind("entry",   entryPrice)
            .bind("exit",    exitPrice)
            .bind("qty",     qty)
            .bind("pnl",     pnl)
            .bind("outcome", outcome.toUpperCase())
            .execute());
    }

    public List<SetupStat> setupPerformance() {
        return jdbi.withHandle(h ->
            h.createQuery("SELECT direction, conviction, total, wins, win_pct, avg_pnl FROM setup_performance")
             .map((rs, ctx) -> new SetupStat(
                     rs.getString("direction"),
                     rs.getString("conviction"),
                     rs.getInt("total"),
                     rs.getInt("wins"),
                     rs.getDouble("win_pct"),
                     rs.getDouble("avg_pnl")))
             .list());
    }

    /**
     * Formats the historical hit-rate table as a compact prompt fragment, or returns an empty
     * string when there is no outcome history yet (so prompts stay clean until data accrues).
     */
    public String performanceContext() {
        List<SetupStat> stats;
        try {
            stats = setupPerformance();
        } catch (Exception e) {
            return "";
        }
        if (stats == null || stats.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (SetupStat s : stats) {
            lines.add(String.format("- %s %s: %.0f%% win over %d trades (avg P&L %.2f)",
                    s.conviction(), s.direction(), s.winPct(), s.total(), s.avgPnl()));
        }
        return "### Historical hit-rate of past setups (your own realized results)\n"
                + String.join("\n", lines) + "\n\n";
    }
}
