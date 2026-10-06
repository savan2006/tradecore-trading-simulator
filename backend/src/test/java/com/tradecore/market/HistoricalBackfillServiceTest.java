package com.tradecore.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.server.ResponseStatusException;

class HistoricalBackfillServiceTest {
    @Test
    void defaultsToTwelveMonthsAndProcessesOneInstrument() {
        HistoricalBackfillProperties properties = properties(12, 5);
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        List<Instrument> selected = List.of(instrument("TCS"));
        when(ingestion.resolveHistoricalBackfillInstruments(null)).thenReturn(selected);
        when(ingestion.backfillDailyCandles(eq(selected.getFirst()), eq(12), any(LocalDate.class)))
                .thenReturn(new MarketDataIngestionResult(30, 20, 0, 10, 0));
        HistoricalBackfillService service = service(ingestion, properties, true);

        HistoricalBackfillStatus result = service.start(null);

        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.months()).isEqualTo(12);
        assertThat(result.fromDate()).isEqualTo(result.throughDate().minusMonths(12));
        assertThat(result.totalBatches()).isEqualTo(1);
        assertThat(result.candlesInserted()).isEqualTo(20);
        verify(ingestion).backfillDailyCandles(selected.getFirst(), 12, result.throughDate());
    }

    @Test
    void usesBoundedSequentialBatchesAndContinuesAfterPerInstrumentTimeout() {
        HistoricalBackfillProperties properties = properties(9, 2);
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        List<Instrument> selected = List.of(instrument("AAA"), instrument("BBB"), instrument("CCC"));
        when(ingestion.resolveHistoricalBackfillInstruments(null)).thenReturn(selected);
        when(ingestion.backfillDailyCandles(any(Instrument.class), eq(9), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    Instrument instrument = invocation.getArgument(0);
                    if (instrument.getSymbol().equals("BBB")) {
                        throw new MarketDataProviderException(MarketDataProviderException.Category.TIMEOUT, "NSE MCP timed out");
                    }
                    return new MarketDataIngestionResult(12, 8, 0, 4, 0);
                });
        HistoricalBackfillService service = service(ingestion, properties, true);

        HistoricalBackfillStatus result = service.start(null);

        assertThat(result.state()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(result.batchSize()).isEqualTo(2);
        assertThat(result.totalBatches()).isEqualTo(2);
        assertThat(result.processedInstruments()).isEqualTo(3);
        assertThat(result.successfulInstruments()).isEqualTo(2);
        assertThat(result.failedInstruments()).isEqualTo(1);
        assertThat(result.candlesReceived()).isEqualTo(24);
        assertThat(result.candlesInserted()).isEqualTo(16);
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.symbol()).isEqualTo("BBB");
            assertThat(failure.category()).isEqualTo("TIMEOUT");
        });
        verify(ingestion).backfillDailyCandles(selected.get(2), 9, result.throughDate());
    }

    @Test
    void recordsPreviouslyCommittedWindowsWhenInstrumentLaterFails() {
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        Instrument tcs = instrument("TCS");
        when(ingestion.resolveHistoricalBackfillInstruments(null)).thenReturn(List.of(tcs));
        when(ingestion.backfillDailyCandles(eq(tcs), eq(4), any(LocalDate.class)))
                .thenThrow(new HistoricalBackfillPartialFailure(
                        new MarketDataProviderException(MarketDataProviderException.Category.TIMEOUT, "timed out"),
                        new MarketDataIngestionResult(11, 9, 0, 2, 0)));
        HistoricalBackfillService service = service(ingestion, properties(12, 5), true);

        HistoricalBackfillStatus result = service.start(new HistoricalBackfillRequest(4, null));

        assertThat(result.state()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(result.candlesReceived()).isEqualTo(11);
        assertThat(result.candlesInserted()).isEqualTo(9);
        assertThat(result.candlesSkipped()).isEqualTo(2);
        assertThat(result.failures().getFirst().category()).isEqualTo("TIMEOUT");
    }

    @Test
    void rejectsConcurrentJobsAndInvalidPeriods() {
        MarketDataIngestionService ingestion = mock(MarketDataIngestionService.class);
        Instrument tcs = instrument("TCS");
        when(ingestion.resolveHistoricalBackfillInstruments(null)).thenReturn(List.of(tcs));
        HistoricalBackfillService service = service(ingestion, properties(12, 5), false);
        service.start(null);
        assertThatThrownBy(() -> service.start(null)).isInstanceOf(ResponseStatusException.class);
        HistoricalBackfillService invalidService = service(ingestion, properties(12, 5), true);
        assertThatThrownBy(() -> invalidService.start(new HistoricalBackfillRequest(37, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static HistoricalBackfillService service(MarketDataIngestionService ingestion,
            HistoricalBackfillProperties properties, boolean runInline) {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            if (runInline) task.run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return new HistoricalBackfillService(ingestion, properties, executor);
    }

    private static HistoricalBackfillProperties properties(int months, int batchSize) {
        HistoricalBackfillProperties properties = new HistoricalBackfillProperties();
        properties.setDefaultMonths(months);
        properties.setInstrumentBatchSize(batchSize);
        return properties;
    }

    private static Instrument instrument(String symbol) {
        Instrument instrument = mock(Instrument.class);
        when(instrument.getSymbol()).thenReturn(symbol);
        return instrument;
    }
}
