package com.tradecore.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.tradecore.execution.OrderExecutionService;
import com.tradecore.order.OrderPlacementService;
import com.tradecore.portfolio.PortfolioQueryService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class MarketDataCacheTest {
    private static final UUID INSTRUMENT_ID = UUID.fromString("7b92e6ec-f58b-49d3-9ca5-0e6b1321a4f6");
    private final StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final MarketDataCacheProperties properties = new MarketDataCacheProperties();
    private final InstrumentRepository instruments = org.mockito.Mockito.mock(InstrumentRepository.class);
    private final MarketQuoteRepository quotes = org.mockito.Mockito.mock(MarketQuoteRepository.class);
    private final MarketCandleRepository candles = org.mockito.Mockito.mock(MarketCandleRepository.class);
    private final Map<String, String> redisValues = new HashMap<>();
    private MarketDataCache cache;
    private MarketDataQueryService query;
    private Instrument instrument;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> redisValues.get(call.getArgument(0)));
        doAnswer(call -> {
            redisValues.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        cache = new MarketDataCache(redis, mapper, properties);
        query = new MarketDataQueryService(instruments, quotes, candles, cache);
        instrument = org.mockito.Mockito.mock(Instrument.class);
        when(instrument.getId()).thenReturn(INSTRUMENT_ID);
        when(instrument.getSymbol()).thenReturn("TCS");
        when(instrument.getExchange()).thenReturn("NSE");
        when(instrument.getCompanyName()).thenReturn("Tata Consultancy Services");
        when(instrument.getInstrumentType()).thenReturn("EQUITY");
        when(instrument.getCurrency()).thenReturn("INR");
        when(instrument.isTradable()).thenReturn(true);
    }

    @Test
    void quoteCacheHitAvoidsPostgresQuoteReadAndRecalculatesFreshness() throws Exception {
        Instant updatedAt = Instant.now().minusSeconds(40);
        cacheJson("tradecore:market:quote:NSE:TCS", new CachedMarketQuote("TCS", "NSE",
                new BigDecimal("100.25"), new BigDecimal("99"), new BigDecimal("101"),
                new BigDecimal("98"), new BigDecimal("97"), 1200L, updatedAt,
                updatedAt, updatedAt.plusSeconds(1), "LIVE"));
        when(instruments.findByExchangeAndSymbol("NSE", "TCS")).thenReturn(Optional.of(instrument));

        MarketQuoteResponse result = query.getQuote("NSE", "TCS");

        assertThat(result.lastPrice()).isEqualByComparingTo("100.25");
        assertThat(result.dataStatus()).isEqualTo("LIVE");
        assertThat(result.freshnessAgeSeconds()).isBetween(39L, 42L);
        verify(quotes, never()).findByInstrument_Id(INSTRUMENT_ID);
    }

    @Test
    void cacheMissReadsPostgresAndStoresOnlyMarketQuoteWithConfiguredTtl() {
        when(instruments.findByExchangeAndSymbol("NSE", "TCS")).thenReturn(Optional.of(instrument));
        MarketQuote quote = persistedQuote("LIVE");
        when(quotes.findByInstrument_Id(INSTRUMENT_ID)).thenReturn(Optional.of(quote));

        MarketQuoteResponse result = query.getQuote("NSE", "TCS");

        assertThat(result.lastPrice()).isEqualByComparingTo("100.25");
        verify(quotes).findByInstrument_Id(INSTRUMENT_ID);
        verify(values).set(eq("tradecore:market:quote:NSE:TCS"), anyString(), eq(Duration.ofSeconds(15)));
        assertThat(redisValues.get("tradecore:market:quote:NSE:TCS")).contains("persistedDataStatus");
    }

    @Test
    void redisUnavailableFallsBackToPostgresWithoutFailingMarketQuery() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis is unavailable"));
        when(instruments.findByExchangeAndSymbol("NSE", "TCS")).thenReturn(Optional.of(instrument));
        MarketQuote quote = persistedQuote("STALE");
        when(quotes.findByInstrument_Id(INSTRUMENT_ID)).thenReturn(Optional.of(quote));

        MarketQuoteResponse result = query.getQuote("NSE", "TCS");

        assertThat(result.lastPrice()).isEqualByComparingTo("100.25");
        assertThat(result.dataStatus()).isEqualTo("STALE");
        verify(quotes).findByInstrument_Id(INSTRUMENT_ID);
    }

    @Test
    void supportedInstrumentListUsesRedisAfterFirstPostgresRead() {
        when(instruments.findAllByExchangeAndTradableTrueOrderBySymbolAsc("NSE"))
                .thenReturn(List.of(instrument));

        List<MarketInstrumentResponse> first = query.listInstruments(null);
        List<MarketInstrumentResponse> second = query.listInstruments(null);

        assertThat(first).containsExactly(new MarketInstrumentResponse(
                "TCS", "Tata Consultancy Services", "NSE", "EQUITY", "INR", true));
        assertThat(second).isEqualTo(first);
        verify(instruments).findAllByExchangeAndTradableTrueOrderBySymbolAsc("NSE");
        verify(values).set(eq("tradecore:market:instruments:all"), anyString(), eq(Duration.ofHours(1)));
        verify(quotes, never()).findByInstrument_Id(any());
    }

    @Test
    void supportedInstrumentLookupUsesRedisAfterFirstPostgresRead() {
        when(instruments.findByExchangeAndSymbol("NSE", "TCS")).thenReturn(Optional.of(instrument));

        MarketInstrumentResponse first = query.getInstrument("NSE", "TCS");
        MarketInstrumentResponse second = query.getInstrument("NSE", "TCS");

        assertThat(second).isEqualTo(first);
        verify(instruments).findByExchangeAndSymbol("NSE", "TCS");
        verify(values).set(eq("tradecore:market:instrument:NSE:TCS"), anyString(), eq(Duration.ofHours(1)));
    }

    @Test
    void ttlSettingsAreConfigurableAndFinancialServicesDoNotUseThisCache() {
        properties.setQuoteTtl(Duration.ofSeconds(7));
        properties.setInstrumentTtl(Duration.ofMinutes(4));
        assertThat(properties.getQuoteTtl()).isEqualTo(Duration.ofSeconds(7));
        assertThat(properties.getInstrumentTtl()).isEqualTo(Duration.ofMinutes(4));

        assertThat(hasMarketCache(PortfolioQueryService.class)).isFalse();
        assertThat(hasMarketCache(OrderPlacementService.class)).isFalse();
        assertThat(hasMarketCache(OrderExecutionService.class)).isFalse();
        assertThat(cache.getClass().getDeclaredMethods()).allSatisfy(method ->
                assertThat(method.getName().toLowerCase()).doesNotContain("account", "balance", "order", "position", "ledger", "pnl"));
    }

    private void cacheJson(String key, CachedMarketQuote quote) throws Exception {
        redisValues.put(key, mapper.writeValueAsString(quote));
    }

    private MarketQuote persistedQuote(String status) {
        MarketQuote quote = org.mockito.Mockito.mock(MarketQuote.class);
        Instant now = Instant.now().minusSeconds(20);
        when(quote.getInstrument()).thenReturn(instrument);
        when(quote.getLastPrice()).thenReturn(new BigDecimal("100.25"));
        when(quote.getOpenPrice()).thenReturn(new BigDecimal("99"));
        when(quote.getHighPrice()).thenReturn(new BigDecimal("101"));
        when(quote.getLowPrice()).thenReturn(new BigDecimal("98"));
        when(quote.getPreviousClose()).thenReturn(new BigDecimal("97"));
        when(quote.getVolume()).thenReturn(1200L);
        when(quote.getMarketAt()).thenReturn(now);
        when(quote.getProviderUpdatedAt()).thenReturn(now);
        when(quote.getReceivedAt()).thenReturn(now.plusSeconds(1));
        when(quote.getDataStatus()).thenReturn(status);
        return quote;
    }

    private static boolean hasMarketCache(Class<?> serviceType) {
        return java.util.Arrays.stream(serviceType.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(MarketDataCache.class));
    }
}
