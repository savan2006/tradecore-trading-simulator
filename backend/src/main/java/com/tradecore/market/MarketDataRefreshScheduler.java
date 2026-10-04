package com.tradecore.market;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled entry points that delegate all provider, validation, and persistence work to ingestion. */
@Component
public class MarketDataRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRefreshScheduler.class);

    private final MarketDataIngestionService ingestionService;
    private final MarketDataRefreshProperties properties;
    private final MarketHoursPolicy marketHoursPolicy;
    private final AtomicBoolean quoteRunning = new AtomicBoolean();
    private final AtomicBoolean candleRunning = new AtomicBoolean();
    private final AtomicReference<Instant> lastSuccessfulQuoteRunAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastSuccessfulCandleRunAt = new AtomicReference<>();
    private final AtomicLong quoteFailures = new AtomicLong();
    private final AtomicLong candleFailures = new AtomicLong();

    public MarketDataRefreshScheduler(MarketDataIngestionService ingestionService,
            MarketDataRefreshProperties properties, MarketHoursPolicy marketHoursPolicy) {
        this.ingestionService = ingestionService;
        this.properties = properties;
        this.marketHoursPolicy = marketHoursPolicy;
    }

    @Scheduled(
            fixedDelayString = "${tradecore.market-data.scheduling.quote-interval:PT5M}",
            initialDelayString = "${tradecore.market-data.scheduling.quote-interval:PT5M}")
    public void scheduledQuoteRefresh() {
        runQuoteRefresh(Instant.now());
    }

    /** Public for controlled operations and deterministic tests; production calls are scheduled above. */
    public void runQuoteRefresh(Instant now) {
        if (!properties.isQuoteEnabled()) {
            return;
        }
        if (!marketHoursPolicy.isRegularSession(now)) {
            log.debug("Market quote refresh skipped outside regular NSE session at {}", now);
            return;
        }
        run("quotes", quoteRunning, quoteFailures, lastSuccessfulQuoteRunAt,
                ingestionService::ingestCurrentQuotes);
    }

    @Scheduled(
            cron = "${tradecore.market-data.scheduling.candle-cron:0 0 18 * * MON-FRI}",
            zone = "${tradecore.market-data.scheduling.timezone:Asia/Kolkata}")
    public void scheduledCandleRefresh() {
        runCandleRefresh();
    }

    /** Runs the existing bounded, idempotent one-month daily-candle ingestion. */
    public void runCandleRefresh() {
        if (!properties.isCandleEnabled()) {
            return;
        }
        run("daily-candles", candleRunning, candleFailures, lastSuccessfulCandleRunAt,
                ingestionService::ingestRecentDailyCandles);
    }

    public Instant getLastSuccessfulQuoteRunAt() { return lastSuccessfulQuoteRunAt.get(); }
    public Instant getLastSuccessfulCandleRunAt() { return lastSuccessfulCandleRunAt.get(); }
    public long getQuoteFailureCount() { return quoteFailures.get(); }
    public long getCandleFailureCount() { return candleFailures.get(); }

    private void run(String jobName, AtomicBoolean running, AtomicLong failures,
            AtomicReference<Instant> lastSuccess, IngestionOperation operation) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Market-data {} refresh skipped because the previous run is still active", jobName);
            return;
        }

        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        log.info("Market-data {} refresh started at {}", jobName, startedAt);
        try {
            MarketDataIngestionResult result = operation.ingest();
            Instant completedAt = Instant.now();
            lastSuccess.set(completedAt);
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            log.info("Market-data {} refresh completed at {} durationMs={} processed={} inserted={} "
                            + "updated={} skipped={} stale={} lastSuccessfulRunAt={}",
                    jobName, completedAt, elapsedMillis, result.received(), result.inserted(), result.updated(),
                    result.skipped(), result.stale(), lastSuccess.get());
        } catch (RuntimeException failure) {
            long failureCount = failures.incrementAndGet();
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            log.error("Market-data {} refresh failed category={} durationMs={} failures={} "
                            + "lastSuccessfulRunAt={}",
                    jobName, failureCategory(failure), elapsedMillis, failureCount, lastSuccess.get(), failure);
        } finally {
            running.set(false);
        }
    }

    private static String failureCategory(RuntimeException failure) {
        if (failure instanceof MarketDataProviderException providerFailure) {
            return providerFailure.category().name();
        }
        if (failure instanceof DataAccessException) {
            return "PERSISTENCE";
        }
        return "APPLICATION";
    }

    @FunctionalInterface
    private interface IngestionOperation {
        MarketDataIngestionResult ingest();
    }
}
