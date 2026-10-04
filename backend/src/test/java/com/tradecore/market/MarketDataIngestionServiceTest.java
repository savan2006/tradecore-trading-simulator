package com.tradecore.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-ingestion-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "tradecore.security.user=ingestion-test",
        "tradecore.security.password=ingestion-test-password"
})
class MarketDataIngestionServiceTest {

    private static final Instant PROVIDER_UPDATED_AT = Instant.parse("2026-10-04T03:32:27.734817172Z");
    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");

    @MockitoBean
    private MarketDataProvider provider;

    @MockitoSpyBean
    private MarketQuoteRepository quoteRepository;

    @Autowired
    private MarketDataIngestionService ingestionService;

    @Autowired
    private MarketCandleRepository candleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearPersistedMarketSnapshots() {
        candleRepository.deleteAll();
        quoteRepository.deleteAll();
        reset(provider);
        reset(quoteRepository);
    }

    @Test
    void persistsAndUpdatesMultipleQuotesWithDistinctProviderTradeAndIngestionTimes() {
        stubQuotes(List.of("TCS", "TRENT"), PROVIDER_UPDATED_AT, Duration.ofDays(30_000));
        Instant beforeIngestion = Instant.now();

        var first = ingestionService.ingestCurrentQuotes(List.of("TCS", "TRENT"));
        var afterIngestion = Instant.now();

        assertThat(first).isEqualTo(new MarketDataIngestionResult(2, 2, 0, 0, 0));
        assertThat(count("market_quote")).isEqualTo(2);
        var stored = jdbcTemplate.queryForMap(
                "SELECT last_price, provider_updated_at, market_at, received_at, market_status, data_status "
                        + "FROM market_quote q JOIN instrument i ON i.id = q.instrument_id WHERE i.symbol = 'TCS'");
        assertThat((BigDecimal) stored.get("last_price")).isEqualByComparingTo("100.25");
        assertThat(toInstant(stored.get("provider_updated_at")))
                .isEqualTo(PROVIDER_UPDATED_AT.truncatedTo(ChronoUnit.MICROS));
        assertThat(toInstant(stored.get("market_at"))).isEqualTo(Instant.parse("2026-10-01T10:30:28Z"));
        assertThat(toInstant(stored.get("received_at"))).isBetween(beforeIngestion, afterIngestion);
        assertThat(stored.get("market_status").toString()).isEqualTo("UNKNOWN");
        assertThat(stored.get("data_status").toString()).isEqualTo("LIVE");
        List<String> originalIds = jdbcTemplate.queryForList(
                "SELECT id FROM market_quote ORDER BY id", String.class);

        var second = ingestionService.ingestCurrentQuotes(List.of("TCS", "TRENT"));

        assertThat(second).isEqualTo(new MarketDataIngestionResult(2, 0, 2, 0, 0));
        assertThat(count("market_quote")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM market_quote ORDER BY id", String.class))
                .containsExactlyElementsOf(originalIds);
    }

    @Test
    void persistsBoundedDailyCandlesAndSkipsExistingRowsWithoutOverwriting() {
        var original = candle("TCS", LocalDate.parse("2026-10-01"), "100", "110", "95", "105", 1200L);
        when(provider.getHistoricalCandles(eq("TCS"), eq(1), eq(null))).thenReturn(List.of(original));

        var first = ingestionService.ingestRecentDailyCandles(List.of("TCS"));

        assertThat(first).isEqualTo(new MarketDataIngestionResult(1, 1, 0, 0, 0));
        assertThat(count("market_candle")).isEqualTo(1);
        var bucketStart = jdbcTemplate.queryForObject(
                "SELECT bucket_start FROM market_candle", java.sql.Timestamp.class).toInstant();
        assertThat(bucketStart).isEqualTo(LocalDate.parse("2026-10-01").atStartOfDay(NSE_ZONE).toInstant());
        assertThat(jdbcTemplate.queryForObject("SELECT resolution FROM market_candle", String.class)).isEqualTo("1D");
        assertThat(jdbcTemplate.queryForObject("SELECT close_price FROM market_candle", BigDecimal.class))
                .isEqualByComparingTo("105");
        assertThat(jdbcTemplate.queryForObject("SELECT volume FROM market_candle", Long.class)).isEqualTo(1200L);

        when(provider.getHistoricalCandles(eq("TCS"), eq(1), eq(null)))
                .thenReturn(List.of(candle("TCS", LocalDate.parse("2026-10-01"), "100", "110", "95", "106", 1300L)));
        var second = ingestionService.ingestRecentDailyCandles(List.of("TCS"));

        assertThat(second).isEqualTo(new MarketDataIngestionResult(1, 0, 0, 1, 0));
        assertThat(jdbcTemplate.queryForObject("SELECT close_price FROM market_candle", BigDecimal.class))
                .isEqualByComparingTo("105");
    }

    @Test
    void rejectsMissingOrInvalidRequiredQuoteValuesWithoutPersisting() {
        var valid = quote("TCS", PROVIDER_UPDATED_AT);
        when(provider.getQuotes(any())).thenReturn(List.of(new MarketQuoteSnapshot(
                valid.exchange(), valid.symbol(), valid.providerInstrumentId(), valid.tradingTimestamp(),
                valid.open(), valid.high(), valid.low(), valid.close(), valid.previousClose(), valid.volume(),
                null, valid.dataSource(), valid.dataUpdatedAt())));
        when(provider.getDataFreshness()).thenReturn(freshness(PROVIDER_UPDATED_AT, Duration.ofDays(30_000)));

        assertThatThrownBy(() -> ingestionService.ingestCurrentQuotes(List.of("TCS")))
                .isInstanceOf(MarketDataProviderException.class)
                .hasMessageContaining("missing required NSE market values");
        assertThat(count("market_quote")).isZero();

        when(provider.getQuotes(any())).thenReturn(List.of(new MarketQuoteSnapshot(
                valid.exchange(), valid.symbol(), valid.providerInstrumentId(), valid.tradingTimestamp(),
                valid.open(), valid.high(), valid.low(), valid.close(), valid.previousClose(), valid.volume(),
                new BigDecimal("-1"), valid.dataSource(), valid.dataUpdatedAt())));
        assertThatThrownBy(() -> ingestionService.ingestCurrentQuotes(List.of("TCS")))
                .isInstanceOf(MarketDataProviderException.class)
                .hasMessageContaining("non-positive last price");
        assertThat(count("market_quote")).isZero();
    }

    @Test
    void propagatesProviderTimeoutAndUnavailableFreshnessWithoutWritingQuotes() {
        when(provider.getQuotes(any())).thenThrow(new MarketDataProviderException(
                MarketDataProviderException.Category.TIMEOUT, "NSE MCP timed out"));

        assertThatThrownBy(() -> ingestionService.ingestCurrentQuotes(List.of("TCS")))
                .isInstanceOfSatisfying(MarketDataProviderException.class,
                        failure -> assertThat(failure.category()).isEqualTo(MarketDataProviderException.Category.TIMEOUT));
        assertThat(count("market_quote")).isZero();

        doReturn(List.of(quote("TCS", PROVIDER_UPDATED_AT))).when(provider).getQuotes(any());
        when(provider.getDataFreshness()).thenReturn(Optional.of(
                new MarketDataFreshness("NSE_MCP_CM_MARKET", false, PROVIDER_UPDATED_AT, Duration.ofMinutes(1))));
        assertThatThrownBy(() -> ingestionService.ingestCurrentQuotes(List.of("TCS")))
                .isInstanceOfSatisfying(MarketDataProviderException.class,
                        failure -> assertThat(failure.category()).isEqualTo(MarketDataProviderException.Category.UNAVAILABLE_DATA));
        assertThat(count("market_quote")).isZero();
    }

    @Test
    void persistsExplicitlyStaleProviderDataWithStaleStatus() {
        Instant oldUpdate = Instant.parse("2024-01-01T00:00:00Z");
        stubQuotes(List.of("TCS"), oldUpdate, Duration.ofMinutes(1));

        var result = ingestionService.ingestCurrentQuotes(List.of("TCS"));

        assertThat(result.stale()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT data_status FROM market_quote", String.class)).isEqualTo("STALE");
        assertThat(jdbcTemplate.queryForObject("SELECT provider_updated_at FROM market_quote", java.sql.Timestamp.class)
                .toInstant()).isEqualTo(oldUpdate);
    }

    @Test
    void rejectsUnknownInstrumentsAndMalformedHistoricalRowsBeforePersistence() {
        assertThatThrownBy(() -> ingestionService.ingestCurrentQuotes(List.of("NOT_APPROVED")))
                .isInstanceOfSatisfying(MarketDataProviderException.class,
                        failure -> assertThat(failure.category()).isEqualTo(MarketDataProviderException.Category.UNAVAILABLE_DATA));
        verifyNoInteractions(provider);

        when(provider.getHistoricalCandles(eq("TCS"), eq(1), eq(null))).thenReturn(List.of(
                new MarketCandleSnapshot("NSE", "TCS", null, LocalDate.parse("2026-10-01"), null,
                        null, new BigDecimal("110"), new BigDecimal("90"), new BigDecimal("100"),
                        10L, new BigDecimal("100"), "NSE_MCP_BHAVCOPY", null)));
        assertThatThrownBy(() -> ingestionService.ingestRecentDailyCandles(List.of("TCS")))
                .isInstanceOf(MarketDataProviderException.class)
                .hasMessageContaining("missing required values");
        assertThat(count("market_candle")).isZero();
    }

    @Test
    void rollsBackAllQuotesWhenOneDatabaseWriteFails() {
        stubQuotes(List.of("TCS", "TRENT"), PROVIDER_UPDATED_AT, Duration.ofDays(30_000));
        String trentId = jdbcTemplate.queryForObject(
                "SELECT id FROM instrument WHERE exchange = 'NSE' AND symbol = 'TRENT'", String.class);
        jdbcTemplate.execute("ALTER TABLE market_quote ADD CONSTRAINT ck_ingestion_rollback_test "
                + "CHECK (instrument_id <> CAST('" + trentId + "' AS UUID))");
        try {
            assertThatThrownBy(() -> ingestionService.ingestCurrentQuotes(List.of("TCS", "TRENT")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("market_quote")).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE market_quote DROP CONSTRAINT ck_ingestion_rollback_test");
        }
    }

    private void stubQuotes(List<String> symbols, Instant updatedAt, Duration refreshInterval) {
        when(provider.getQuotes(any())).thenReturn(symbols.stream().map(symbol -> quote(symbol, updatedAt)).toList());
        when(provider.getDataFreshness()).thenReturn(Optional.of(
                new MarketDataFreshness("NSE_MCP_CM_MARKET", true, updatedAt, refreshInterval)));
    }

    private static MarketQuoteSnapshot quote(String symbol, Instant updatedAt) {
        return new MarketQuoteSnapshot("NSE", symbol, null,
                Instant.parse("2026-10-01T10:30:28Z"), new BigDecimal("95"), new BigDecimal("110"),
                new BigDecimal("90"), null, new BigDecimal("94"), 1000L, new BigDecimal("100.25"),
                "NSE_MCP_CM_MARKET", updatedAt);
    }

    private static MarketCandleSnapshot candle(String symbol, LocalDate date,
            String open, String high, String low, String close, long volume) {
        return new MarketCandleSnapshot("NSE", symbol, null, date, null,
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
                volume, new BigDecimal(close), "NSE_MCP_BHAVCOPY", null);
    }

    private static Optional<MarketDataFreshness> freshness(Instant updatedAt, Duration interval) {
        return Optional.of(new MarketDataFreshness("NSE_MCP_CM_MARKET", true, updatedAt, interval));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new AssertionError("Unexpected timestamp type " + value.getClass());
    }
}
