package com.tradecore.strategylab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradecore.market.Instrument;
import com.tradecore.market.InstrumentRepository;
import com.tradecore.market.MarketCandle;
import com.tradecore.market.MarketCandleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StrategyBacktestServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate START = LocalDate.now(ZONE).minusDays(90);

    @Test
    void smaUsesNextCandleOpenAndIsDeterministic() {
        List<MarketCandle> data = candles(START, new String[]{"100", "100", "100", "80", "90", "110", "120", "130", "90", "110"},
                new String[]{"100", "100", "100", "80", "90", "110", "120", "130", "90", "140"});
        BacktestRequest request = sma(START, START.plusDays(9), "10000", 2, 3);
        StrategyBacktestService service = service(data);

        BacktestResponse first = service.run(request);
        BacktestResponse second = service.run(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.numberOfTrades()).isEqualTo(1);
        assertThat(first.simulatedTrades()).singleElement().satisfies(trade -> {
            assertThat(trade.entryDate()).isEqualTo(START.plusDays(6));
            assertThat(trade.entryPrice()).isEqualByComparingTo("120");
            assertThat(trade.exitDate()).isEqualTo(START.plusDays(9));
            assertThat(trade.exitPrice()).isEqualByComparingTo("140");
            assertThat(trade.realizedResult()).isPositive();
        });
        assertThat(first.realizedPnl()).isPositive();
    }

    @Test
    void smaCanRecordLosingTradeAndMaximumDrawdown() {
        List<MarketCandle> data = candles(START, new String[]{"100", "100", "100", "80", "90", "110", "120", "130", "90", "80"},
                new String[]{"100", "100", "100", "80", "90", "110", "120", "130", "90", "80"});
        BacktestResponse result = service(data).run(sma(START, START.plusDays(9), "10000", 2, 3));
        assertThat(result.losingTrades()).isEqualTo(1);
        assertThat(result.realizedPnl()).isNegative();
        assertThat(result.maximumDrawdownPercent()).isPositive();
    }

    @Test
    void smaAllowsMultipleCompletedTrades() {
        String[] prices = {"100", "100", "100", "80", "90", "110", "120", "130", "90", "110", "130", "80", "70", "60", "90", "100", "110", "120", "80", "70"};
        BacktestResponse result = service(candles(START, prices, prices)).run(sma(START, START.plusDays(19), "10000", 2, 3));
        assertThat(result.numberOfTrades()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rsiMeanReversionUsesNextCandleOpen() {
        String[] prices = {"100", "90", "80", "70", "60", "50", "55", "60", "65", "70", "75", "80", "85", "90", "95", "100", "105", "110"};
        BacktestRequest request = new BacktestRequest("TCS", START, START.plusDays(prices.length - 1),
                BacktestStrategy.RSI_MEAN_REVERSION, new BigDecimal("10000"), null, null, 3,
                new BigDecimal("30"), new BigDecimal("70"));
        BacktestResponse result = service(candles(START, prices, prices)).run(request);
        assertThat(result.simulatedTrades()).isNotEmpty();
        assertThat(result.simulatedTrades().getFirst().entryDate()).isEqualTo(START.plusDays(8));
        assertThat(result.simulatedTrades().getFirst().entryPrice()).isEqualByComparingTo(prices[8]);
    }

    @Test
    void benchmarkUsesFirstPeriodOpenAndLastPeriodClose() {
        String[] prices = {"10", "12", "15", "20"};
        BacktestRequest request = new BacktestRequest("TCS", START, START.plusDays(3),
                BacktestStrategy.RSI_MEAN_REVERSION, new BigDecimal("100"), null, null, 2,
                new BigDecimal("30"), new BigDecimal("70"));
        BacktestResponse result = service(candles(START, prices, prices)).run(request);
        assertThat(result.buyAndHoldReturnPercent()).isEqualByComparingTo("100.0000");
        assertThat(result.equityCurve()).hasSize(4);
    }

    @Test
    void rejectsInsufficientCandlesEmptyRangeUnsupportedAndInvalidInputs() {
        BacktestRequest valid = sma(START, START.plusDays(2), "1000", 2, 3);
        assertThatThrownBy(() -> service(candles(START, new String[]{"10", "11"}, new String[]{"10", "11"})).run(valid))
                .isInstanceOf(ResponseStatusException.class).extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThatThrownBy(() -> service(List.of()).run(valid)).isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThatThrownBy(() -> service(candles(START, new String[]{"10", "11", "12", "13"}, new String[]{"10", "11", "12", "13"}))
                .run(sma(START.plusDays(10), START.plusDays(12), "1000", 2, 3))).isInstanceOf(ResponseStatusException.class);
        assertBadRequest(() -> service(List.of()).run(sma(START, START.minusDays(1), "1000", 2, 3)));
        assertBadRequest(() -> service(List.of()).run(sma(START, LocalDate.now(ZONE).plusDays(1), "1000", 2, 3)));
        assertBadRequest(() -> service(List.of()).run(sma(START, START.plusDays(3), "0", 2, 3)));
        assertBadRequest(() -> service(List.of()).run(sma(START, START.plusDays(3), "1000", 3, 3)));
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.findByExchangeAndSymbol("NSE", "UNKNOWN")).thenReturn(Optional.empty());
        StrategyBacktestService unsupported = new StrategyBacktestService(instruments, mock(MarketCandleRepository.class));
        assertThatThrownBy(() -> unsupported.run(new BacktestRequest("UNKNOWN", START, START.plusDays(3),
                BacktestStrategy.SIMPLE_MOVING_AVERAGE_CROSSOVER, new BigDecimal("1000"), 2, 3, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static BacktestRequest sma(LocalDate from, LocalDate to, String capital, int fast, int slow) {
        return new BacktestRequest("TCS", from, to, BacktestStrategy.SIMPLE_MOVING_AVERAGE_CROSSOVER,
                new BigDecimal(capital), fast, slow, null, null, null);
    }

    private static StrategyBacktestService service(List<MarketCandle> data) {
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        MarketCandleRepository candles = mock(MarketCandleRepository.class);
        Instrument instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn(UUID.randomUUID());
        when(instrument.isTradable()).thenReturn(true);
        when(instruments.findByExchangeAndSymbol("NSE", "TCS")).thenReturn(Optional.of(instrument));
        when(candles.findAllByInstrument_IdAndResolutionAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                any(), anyString(), any(Instant.class), any(Instant.class), any(Pageable.class))).thenReturn(data);
        return new StrategyBacktestService(instruments, candles);
    }

    private static List<MarketCandle> candles(LocalDate start, String[] closes, String[] opens) {
        java.util.ArrayList<MarketCandle> result = new java.util.ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            MarketCandle candle = mock(MarketCandle.class);
            when(candle.getBucketStart()).thenReturn(start.plusDays(i).atStartOfDay(ZONE).toInstant());
            when(candle.getOpenPrice()).thenReturn(new BigDecimal(opens[i]));
            when(candle.getHighPrice()).thenReturn(new BigDecimal(opens[i]).max(new BigDecimal(closes[i])));
            when(candle.getLowPrice()).thenReturn(new BigDecimal(opens[i]).min(new BigDecimal(closes[i])));
            when(candle.getClosePrice()).thenReturn(new BigDecimal(closes[i]));
            result.add(candle);
        }
        return result;
    }
}
