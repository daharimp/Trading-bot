package com.tradingbot.scheduler;

import com.tradingbot.alpaca.AlpacaClient;
import com.tradingbot.analysis.AnalysisService;
import com.tradingbot.db.WatchlistDao;
import com.tradingbot.discord.DiscordNotifier;
import com.tradingbot.kraken.KrakenClient;
import com.tradingbot.model.TradeIdea;
import com.tradingbot.order.OrderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WatchlistScheduler {

    private static final Logger log = LoggerFactory.getLogger(WatchlistScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final double GAP_THRESHOLD = 0.01; // 1% gap triggers skip

    private final DiscordNotifier notifier;
    private final AlpacaClient alpaca;
    private final AnalysisService analysisService;
    private final OrderManager orderManager;
    private final WatchlistDao watchlistDao;
    private final String scheduleTime;
    private final int defaultQty;
    private KrakenClient kraken;

    /** Injects the Kraken client for crypto gap-checks at auto-play time. */
    public void setKrakenClient(KrakenClient krakenClient) {
        this.kraken = krakenClient;
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "watchlist-scheduler");
        t.setDaemon(true);
        return t;
    });

    public WatchlistScheduler(DiscordNotifier notifier, AlpacaClient alpaca,
                               AnalysisService analysisService, OrderManager orderManager,
                               WatchlistDao watchlistDao,
                               String scheduleTime, int defaultQty) {
        this.notifier        = notifier;
        this.alpaca          = alpaca;
        this.analysisService = analysisService;
        this.orderManager    = orderManager;
        this.watchlistDao    = watchlistDao;
        this.scheduleTime    = scheduleTime;
        this.defaultQty      = defaultQty;
    }

    public void addTicker(String ticker, boolean autoPlay) {
        watchlistDao.add(ticker, autoPlay);
    }

    public void removeTicker(String ticker) {
        watchlistDao.remove(ticker);
    }

    /** Returns ticker strings for display, with (auto) suffix where applicable. */
    public List<String> getWatchlist() {
        List<String> entries = new ArrayList<>();
        watchlistDao.loadAll().forEach((ticker, auto) -> entries.add(auto ? ticker + " (auto)" : ticker));
        return entries;
    }

    public boolean isAutoPlay(String ticker) {
        return watchlistDao.isAutoPlay(ticker);
    }

    public boolean isEmpty() {
        return watchlistDao.isEmpty();
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
        List<String> tickers = new ArrayList<>(watchlistDao.loadAll().keySet());
        if (tickers.isEmpty()) {
            log.info("Watchlist is empty — skipping scheduled analysis");
            return;
        }

        log.info("Starting overnight analysis for {} ticker(s): {}", tickers.size(), tickers);
        notifier.postMessage("🌙 **Overnight Analysis Starting** — " + tickers.size()
                + " ticker(s): " + String.join(", ", getWatchlist()));

        Map<String, Map<AlpacaClient.Timeframe, BarSeries>> batchedBars =
                analysisService.prefetchStockBars(tickers);

        for (String ticker : tickers) {
            try {
                log.info("Analyzing {}", ticker);
                Map<AlpacaClient.Timeframe, BarSeries> preloaded = batchedBars.get(ticker);
                AnalysisService.AnalysisResult result = analysisService.runFullAnalysis(ticker, preloaded);

                notifier.postMessage(result.text());

                String pickMenu = analysisService.buildPickMenu(result.ideas());
                if (!pickMenu.isEmpty()) {
                    notifier.postMessage(pickMenu);
                }

                if (isAutoPlay(ticker)) {
                    autoPlace(ticker, result.ideas());
                }

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
        boolean crypto = AlpacaClient.isCrypto(ticker);

        double mid = 0;
        try {
            if (crypto && kraken != null) {
                KrakenClient.Quote q = kraken.getLatestQuote(ticker);
                if (q != null) mid = q.mid();
            } else if (!crypto) {
                AlpacaClient.Quote q = alpaca.getLatestQuote(ticker);
                if (q != null) mid = q.mid();
            }
        } catch (Exception e) {
            log.warn("Gap-check quote fetch failed for {}: {} — proceeding without gap check", ticker, e.getMessage());
        }

        List<TradeIdea> highConviction = ideas.stream()
                .filter(i -> i.getConviction() == TradeIdea.Conviction.HIGH)
                .toList();

        if (highConviction.isEmpty()) {
            notifier.postMessage("🤖 **AUTO-PLAY** (" + ticker + "): No HIGH conviction setups to place.");
            return;
        }

        for (TradeIdea idea : highConviction) {
            try {
                if (mid > 0) {
                    double gap = Math.abs(mid - idea.getEntry()) / idea.getEntry();
                    if (gap > GAP_THRESHOLD) {
                        log.info("skipped_gap_check: {} gap={}% entry={} mid={}",
                                ticker, String.format("%.1f", gap * 100), idea.getEntry(), mid);
                        notifier.postMessage(String.format(
                                "⏭️ **AUTO-PLAY SKIPPED** (%s): %.1f%% overnight gap (entry $%.2f vs mid $%.2f). " +
                                "Review manually: 👆",
                                ticker, gap * 100, idea.getEntry(), mid));
                        continue;
                    }
                }

                int qty = idea.getSuggestedQty() > 0 ? idea.getSuggestedQty() : defaultQty;
                String confirmation = orderManager.placePlayOpg(
                        idea.getTicker(),
                        idea.getDirection().name(),
                        idea.getEntry(),
                        idea.getStopLoss(),
                        idea.getTarget(),
                        qty,
                        idea.getConviction().name());
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
