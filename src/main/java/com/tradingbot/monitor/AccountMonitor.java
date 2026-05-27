package com.tradingbot.monitor;

import com.tradingbot.kraken.KrakenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class AccountMonitor {

    private static final Logger log = LoggerFactory.getLogger(AccountMonitor.class);

    private final KrakenClient kraken;
    private final double minThreshold;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "account-monitor");
                t.setDaemon(true);
                return t;
            });

    private Consumer<String> alertCallback = msg -> {};

    public AccountMonitor(KrakenClient kraken, double minThreshold) {
        this.kraken = kraken;
        this.minThreshold = minThreshold;
    }

    public void setAlertCallback(Consumer<String> callback) {
        this.alertCallback = callback;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::checkBalance, 0, 5, TimeUnit.MINUTES);
        log.info("AccountMonitor started — threshold ${}", minThreshold);
    }

    private void checkBalance() {
        try {
            double total = getTotalUsdValue();
            log.debug("AccountMonitor: balance=${}", String.format("%.2f", total));
            if (total > 0 && total < minThreshold && !paused.get()) {
                paused.set(true);
                int cancelled = kraken.cancelAllOpenOrders();
                String alert = String.format(
                    "⚠️ Balance dropped to $%.2f (below $%.2f threshold). " +
                    "%d pending orders cancelled. Bot paused — use !resume to re-enable.",
                    total, minThreshold, cancelled);
                log.warn("Circuit breaker triggered: {}", alert);
                alertCallback.accept(alert);
            }
        } catch (Exception e) {
            log.error("AccountMonitor check failed", e);
        }
    }

    public double getTotalUsdValue() {
        Map<String, Double> balances = kraken.getAccountBalance();
        if (balances.isEmpty()) return 0;
        double total = balances.getOrDefault("ZUSD", 0.0);
        Map<String, Double> positions = kraken.getOpenPositions();
        for (Map.Entry<String, Double> pos : positions.entrySet()) {
            try {
                double mid = kraken.getLatestQuote(pos.getKey()).mid();
                total += pos.getValue() * mid;
            } catch (Exception e) {
                log.warn("Could not price {} for balance estimate", pos.getKey());
            }
        }
        return total;
    }

    public boolean isPaused() { return paused.get(); }

    public String unpause() {
        paused.set(false);
        double balance = getTotalUsdValue();
        String msg;
        if (balance > 0 && balance < minThreshold) {
            msg = String.format("✅ Bot resumed. ⚠️ Warning: balance $%.2f still below threshold $%.2f.", balance, minThreshold);
        } else {
            msg = String.format("✅ Bot resumed. Current balance: $%.2f", balance);
        }
        log.info("Bot unpaused. {}", msg);
        return msg;
    }

    public void stop() { scheduler.shutdownNow(); }
}
