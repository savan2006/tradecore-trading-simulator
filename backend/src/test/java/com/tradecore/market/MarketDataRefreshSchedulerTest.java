package com.tradecore.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

class MarketDataRefreshSchedulerTest {

    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");

    @Test
    void quoteRefreshDelegatesTheFullUniverseOnlyDuringConfiguredMarketHours() {
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        MarketDataRefreshProperties properties = new MarketDataRefreshProperties();
        MarketDataRefreshScheduler scheduler = scheduler(ingestion, properties);
        when(ingestion.ingestCurrentQuotes()).thenReturn(result(80, 0, 80, 0, 0));

        scheduler.runQuoteRefresh(at("2026-10-05T09:15:00"));

        verify(ingestion).ingestCurrentQuotes();
        assertThat(scheduler.getLastSuccessfulQuoteRunAt()).isNotNull();
        assertThat(scheduler.getQuoteFailureCount()).isZero();
    }

    @Test
    void candleRefreshDelegatesToTheExistingBoundedIngestionOperation() {
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        MarketDataRefreshScheduler scheduler = scheduler(ingestion, new MarketDataRefreshProperties());
        when(ingestion.ingestRecentDailyCandles()).thenReturn(result(80, 0, 0, 80, 0));

        scheduler.runCandleRefresh();

        verify(ingestion).ingestRecentDailyCandles();
        assertThat(scheduler.getLastSuccessfulCandleRunAt()).isNotNull();
    }

    @Test
    void disabledJobsDoNotInvokeIngestion() {
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        MarketDataRefreshProperties properties = new MarketDataRefreshProperties();
        properties.setQuoteEnabled(false);
        properties.setCandleEnabled(false);
        MarketDataRefreshScheduler scheduler = scheduler(ingestion, properties);

        scheduler.runQuoteRefresh(at("2026-10-05T10:00:00"));
        scheduler.runCandleRefresh();

        verifyNoInteractions(ingestion);
    }

    @Test
    void regularSessionUsesAsiaKolkataWeekdaysAndHalfOpenBoundaries() {
        MarketHoursPolicy policy = new MarketHoursPolicy(new MarketDataRefreshProperties());

        assertThat(policy.isRegularSession(at("2026-10-05T09:14:59"))).isFalse();
        assertThat(policy.isRegularSession(at("2026-10-05T09:15:00"))).isTrue();
        assertThat(policy.isRegularSession(at("2026-10-05T15:29:59"))).isTrue();
        assertThat(policy.isRegularSession(at("2026-10-05T15:30:00"))).isFalse();
        assertThat(policy.isRegularSession(at("2026-10-03T10:00:00"))).isFalse();
    }

    @Test
    void outsideMarketHoursQuoteRefreshDoesNotCallIngestion() {
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        MarketDataRefreshScheduler scheduler = scheduler(ingestion, new MarketDataRefreshProperties());

        scheduler.runQuoteRefresh(at("2026-10-05T16:00:00"));

        verifyNoInteractions(ingestion);
    }

    @Test
    void concurrentQuoteExecutionIsSkippedAndLockIsReleasedAfterCompletion() throws Exception {
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        MarketDataRefreshScheduler scheduler = scheduler(ingestion, new MarketDataRefreshProperties());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ingestion.ingestCurrentQuotes()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timed out");
            }
            return result(80, 0, 80, 0, 0);
        });
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread running = new Thread(() -> {
            try {
                scheduler.runQuoteRefresh(at("2026-10-05T10:00:00"));
            } catch (Throwable failure) {
                threadFailure.set(failure);
            }
        });

        running.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.runQuoteRefresh(at("2026-10-05T10:00:00"));
        } finally {
            release.countDown();
            running.join(3000);
        }

        assertThat(running.isAlive()).isFalse();
        assertThat(threadFailure.get()).isNull();
        verify(ingestion, times(1)).ingestCurrentQuotes();
        scheduler.runQuoteRefresh(at("2026-10-05T10:00:00"));
        verify(ingestion, times(2)).ingestCurrentQuotes();
    }

    @Test
    void providerFailureDoesNotPreventTheNextScheduledRun() {
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        MarketDataRefreshScheduler scheduler = scheduler(ingestion, new MarketDataRefreshProperties());
        when(ingestion.ingestCurrentQuotes())
                .thenThrow(new MarketDataProviderException(MarketDataProviderException.Category.TIMEOUT,
                        "controlled timeout"))
                .thenReturn(result(80, 0, 80, 0, 0));

        scheduler.runQuoteRefresh(at("2026-10-05T10:00:00"));
        assertThat(scheduler.getQuoteFailureCount()).isEqualTo(1);
        assertThat(scheduler.getLastSuccessfulQuoteRunAt()).isNull();

        scheduler.runQuoteRefresh(at("2026-10-05T10:05:00"));

        verify(ingestion, times(2)).ingestCurrentQuotes();
        assertThat(scheduler.getLastSuccessfulQuoteRunAt()).isNotNull();
        assertThat(scheduler.getQuoteFailureCount()).isEqualTo(1);
    }

    @Test
    void refreshConfigurationBindsIntervalsSchedulesAndSessionBoundaries() {
        var environment = new StandardEnvironment();
        var source = new MapPropertySource("test", java.util.Map.of(
                "tradecore.market-data.scheduling.quote-enabled", "false",
                "tradecore.market-data.scheduling.quote-interval", "PT7M",
                "tradecore.market-data.scheduling.candle-enabled", "true",
                "tradecore.market-data.scheduling.candle-cron", "0 30 17 * * MON-FRI",
                "tradecore.market-data.scheduling.timezone", "Asia/Kolkata",
                "tradecore.market-data.scheduling.regular-session-open", "09:20",
                "tradecore.market-data.scheduling.regular-session-close", "15:25"));
        environment.getPropertySources().addFirst(source);

        MarketDataRefreshProperties properties = Binder.get(environment)
                .bind("tradecore.market-data.scheduling", Bindable.of(MarketDataRefreshProperties.class))
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.isQuoteEnabled()).isFalse();
        assertThat(properties.getQuoteInterval()).isEqualTo(java.time.Duration.ofMinutes(7));
        assertThat(properties.isCandleEnabled()).isTrue();
        assertThat(properties.getCandleCron()).isEqualTo("0 30 17 * * MON-FRI");
        assertThat(properties.getTimezone()).isEqualTo("Asia/Kolkata");
        assertThat(properties.getRegularSessionOpen()).hasToString("09:20");
        assertThat(properties.getRegularSessionClose()).hasToString("15:25");
    }

    private static MarketDataRefreshScheduler scheduler(
            MarketDataIngestionService ingestion, MarketDataRefreshProperties properties) {
        return new MarketDataRefreshScheduler(ingestion, properties, new MarketHoursPolicy(properties));
    }

    private static MarketDataIngestionResult result(
            int received, int inserted, int updated, int skipped, int stale) {
        return new MarketDataIngestionResult(received, inserted, updated, skipped, stale);
    }

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(NSE_ZONE).toInstant();
    }
}
