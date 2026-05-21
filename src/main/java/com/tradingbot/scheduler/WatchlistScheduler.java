package com.tradingbot.scheduler;

import com.tradingbot.analysis.AnalysisService;
import com.tradingbot.discord.DiscordNotifier;
import com.tradingbot.model.TradeIdea;
import com.tradingbot.order.OrderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WatchlistScheduler {

    private static final Logger log = LoggerFactory.getLogger(WatchlistScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final DiscordNotifier notifier;
    private final AnalysisService analysisService;
    private final OrderManager orderManager;
    private final String scheduleTime;
    private final int defaultQty;

    // key = ticker (normalized upper), value = autoPlay flag
    private final Map<String, Boolean> watchlist = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "watchlist-scheduler");
        t.setDaemon(true);
        return t;
    });

    public WatchlistScheduler(DiscordNotifier notifier, AnalysisService analysisService,
                               OrderManager orderManager, String scheduleTime, int defaultQty) {
        this.notifier        = notifier;
        this.analysisService = analysisService;
        this.orderManager    = orderManager;
        this.scheduleTime    = scheduleTime;
        this.defaultQty      = defaultQty;
    }

    public void addTicker(String ticker, boolean autoPlay) {
        watchlist.put(ticker.toUpperCase(), autoPlay);
    }

    public void removeTicker(String ticker) {
        watchlist.remove(ticker.toUpperCase());
    }

    /** Returns ticker strings for display, with (auto) suffix where applicable. */
    public List<String> getWatchlist() {
        List<String> entries = new ArrayList<>();
        watchlist.forEach((ticker, auto) -> entries.add(auto ? ticker + " (auto)" : ticker));
        return entries;
    }

    public boolean isAutoPlay(String ticker) {
        return Boolean.TRUE.equals(watchlist.get(ticker.toUpperCase()));
    }

    public boolean isEmpty() {
        return watchlist.isEmpty();
    }

    /** Schedules the daily run at the configured ET time. */
    public void start() {
        long initialDelay = secondsUntilNextRun();
        log.info("Watchlist scheduler armed — next run in {}s ({}ET)", initialDelay, scheduleTime);
        scheduler.scheduleAtFixedRate(this::runAnalysis, initialDelay,
                TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    /** Manually triggered by !runanalysis — runs immediately in background. */
    public void runNow() {
        scheduler.submit(this::runAnalysis);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void runAnalysis() {
        List<String> tickers = new ArrayList<>(watchlist.keySet());
        if (tickers.isEmpty()) {
            log.info("Watchlist is empty — skipping scheduled analysis");
            return;
        }

        log.info("Starting overnight analysis for {} ticker(s): {}", tickers.size(), tickers);
        notifier.postMessage("🌙 **Overnight Analysis Starting** — " + tickers.size()
                + " ticker(s): " + String.join(", ", getWatchlist()));

        for (String ticker : tickers) {
            try {
                log.info("Analyzing {}", ticker);
                AnalysisService.AnalysisResult result = analysisService.runFullAnalysis(ticker);

                notifier.postMessage(result.text());

                String pickMenu = analysisService.buildPickMenu(result.ideas());
                if (!pickMenu.isEmpty()) {
                    notifier.postMessage(pickMenu);
                }

                if (isAutoPlay(ticker)) {
                    autoPlace(ticker, result.ideas());
                }

                // Space requests to respect Alpha Vantage rate limits
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Analysis failed for {}: {}", ticker, e.getMessage());
                notifier.postMessage("❌ Analysis failed for **" + ticker + "**: " + e.getMessage());
            }
        }

        notifier.postMessage("✅ **Overnight analysis complete.** Good morning — review your setups above.");
    }

    private void autoPlace(String ticker, List<TradeIdea> ideas) {
        List<TradeIdea> highConviction = ideas.stream()
                .filter(i -> i.getConviction() == TradeIdea.Conviction.HIGH)
                .toList();

        if (highConviction.isEmpty()) {
            notifier.postMessage("🤖 **AUTO-PLAY** (" + ticker + "): No HIGH conviction setups to place.");
            return;
        }

        for (TradeIdea idea : highConviction) {
            try {
                String confirmation = orderManager.placePlay(
                        idea.getTicker(),
                        idea.getDirection().name(),
                        idea.getEntry(),
                        idea.getStopLoss(),
                        idea.getTarget(),
                        defaultQty);
                notifier.postMessage("🤖 **AUTO-PLAY** " + confirmation);
            } catch (Exception e) {
                log.error("Auto-play order failed for {}: {}", ticker, e.getMessage());
                notifier.postMessage("⚠️ Auto-play failed for **" + ticker + "**: " + e.getMessage());
            }
        }
    }

    private long secondsUntilNextRun() {
        LocalTime target;
        try {
            target = LocalTime.parse(scheduleTime, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            log.warn("Invalid ANALYSIS_SCHEDULE_TIME '{}', defaulting to 23:00", scheduleTime);
            target = LocalTime.of(23, 0);
        }

        ZonedDateTime now  = ZonedDateTime.now(ET);
        ZonedDateTime next = now.withHour(target.getHour()).withMinute(target.getMinute())
                               .withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);

        return next.toEpochSecond() - now.toEpochSecond();
    }
}
