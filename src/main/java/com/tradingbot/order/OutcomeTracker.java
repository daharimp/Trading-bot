package com.tradingbot.order;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.db.OrderDao;
import com.tradingbot.db.OrderDao.OpenOrder;
import com.tradingbot.db.PerformanceDao;
import com.tradingbot.kraken.KrakenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls placed bracket orders and records a realized outcome when a position closes, populating
 * position_outcomes so the setup_performance view (fed into the LLM prompt) has real data.
 *
 * <p>Exit price is taken from the stored stop/target level (decision: "from stop/target levels"),
 * not the actual fill — so PnL ignores exit slippage. Alpaca: which leg filled is read from the
 * nested bracket. Kraken: the take-profit leg is read via QueryOrders; a stop-out is inferred when
 * the entry has filled but the position is no longer open.
 */
public class OutcomeTracker {

    private static final Logger log = LoggerFactory.getLogger(OutcomeTracker.class);
    private static final int POLL_INTERVAL_SECONDS = 60;

    private final AlpacaClient alpaca;
    private final KrakenClient kraken;       // may be null if crypto not configured
    private final OrderDao orderDao;
    private final PerformanceDao performanceDao;

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outcome-tracker");
        t.setDaemon(true);
        return t;
    });

    public OutcomeTracker(AlpacaClient alpaca, KrakenClient kraken,
                          OrderDao orderDao, PerformanceDao performanceDao) {
        this.alpaca = alpaca;
        this.kraken = kraken;
        this.orderDao = orderDao;
        this.performanceDao = performanceDao;
    }

    public void start() {
        poller.scheduleAtFixedRate(this::poll, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("OutcomeTracker started — polling every {}s", POLL_INTERVAL_SECONDS);
    }

    public void shutdown() {
        poller.shutdownNow();
    }

    private void poll() {
        List<OpenOrder> orders;
        try {
            orders = orderDao.listOpenForOutcome();
        } catch (Exception e) {
            log.warn("OutcomeTracker: could not load open orders: {}", e.getMessage());
            return;
        }
        if (orders.isEmpty()) return;

        // Fetch Kraken open positions once per poll for the stop-out inference path.
        Map<String, Double> krakenPositions = (kraken != null) ? safeKrakenPositions() : Map.of();

        for (OpenOrder o : orders) {
            try {
                if ("ALPACA".equalsIgnoreCase(o.broker())) {
                    handleAlpaca(o);
                } else if ("KRAKEN".equalsIgnoreCase(o.broker())) {
                    handleKraken(o, krakenPositions);
                }
            } catch (Exception e) {
                log.warn("OutcomeTracker: failed on order {} ({}): {}", o.id(), o.ticker(), e.getMessage());
            }
        }
    }

    private void handleAlpaca(OpenOrder o) throws Exception {
        AlpacaClient.CloseLeg leg = alpaca.getBracketCloseLeg(o.orderId());
        if (leg == AlpacaClient.CloseLeg.NONE) return;
        double exit = (leg == AlpacaClient.CloseLeg.TARGET) ? o.target() : o.stop();
        record(o, exit, leg == AlpacaClient.CloseLeg.TARGET);
    }

    private void handleKraken(OpenOrder o, Map<String, Double> positions) {
        if (kraken == null) return;
        Map<String, String> statuses =
                kraken.queryOrderStatuses(List.of(o.orderId(), o.tpOrderId() == null ? "" : o.tpOrderId()));

        String entryStatus = statuses.get(o.orderId());
        String tpStatus    = o.tpOrderId() != null ? statuses.get(o.tpOrderId()) : null;

        // Take-profit filled → win at target (deterministic via QueryOrders).
        if ("closed".equals(tpStatus)) {
            record(o, o.target(), true);
            return;
        }

        // Entry must have filled (status closed = entry executed) before a stop-out is possible.
        boolean entryFilled = "closed".equals(entryStatus);
        if (!entryFilled) return;

        // Entry filled, TP still open: if the position is gone, it was stopped out → loss at stop.
        boolean positionGone = positions.getOrDefault(o.ticker().toUpperCase(), 0.0) < 1e-8;
        if (positionGone) {
            record(o, o.stop(), false);
        }
    }

    /** Computes PnL from stored levels, writes the outcome, and marks the order closed. */
    private void record(OpenOrder o, double exit, boolean win) {
        boolean isLong = "LONG".equalsIgnoreCase(o.direction());
        double pnl = (isLong ? (exit - o.entry()) : (o.entry() - exit)) * o.qty();
        String outcome = pnl > 0 ? "WIN" : (pnl < 0 ? "LOSS" : "BREAKEVEN");
        // Trust the leg that fired for WIN/LOSS even if rounding makes pnl ~0.
        if (win && "BREAKEVEN".equals(outcome)) outcome = "WIN";
        if (!win && "BREAKEVEN".equals(outcome)) outcome = "LOSS";

        try {
            performanceDao.recordOutcome(o.id(), o.ticker(), o.direction(),
                    o.entry(), exit, o.qty(), pnl, outcome, o.conviction());
            log.info("outcome recorded: {} {} {} exit={} pnl={} conviction={}",
                    o.ticker(), o.direction(), outcome, String.format("%.4f", exit),
                    String.format("%.2f", pnl), o.conviction());
        } catch (Exception e) {
            // V4 UNIQUE index on position_outcomes.order_id makes a duplicate insert throw.
            // That happens only if a previous poll wrote the outcome but crashed before
            // markClosed — treat as "already recorded" and proceed to close the order.
            log.info("outcome already recorded for order {} ({}), closing", o.id(), o.ticker());
        }
        orderDao.markClosed(o.id());
    }

    private Map<String, Double> safeKrakenPositions() {
        try {
            return kraken.getOpenPositions();
        } catch (Exception e) {
            log.warn("OutcomeTracker: Kraken positions unavailable: {}", e.getMessage());
            return Map.of();
        }
    }
}
