package com.tradecore.market;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-market-query-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "tradecore.security.user=market-api-test",
        "tradecore.security.password=market-api-password"
})
@AutoConfigureMockMvc
class MarketDataQueryControllerTest {

    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Autowired
    private MarketQuoteRepository quoteRepository;

    @Autowired
    private MarketCandleRepository candleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private MarketQuoteRepository quoteRepositorySpy;

    @MockitoBean
    private MarketDataProvider provider;

    @BeforeEach
    void seedDeterministicPersistedData() {
        candleRepository.deleteAll();
        quoteRepository.deleteAll();
        reset(quoteRepositorySpy);

        Instant now = Instant.now();
        saveQuote("TCS", now.minusSeconds(60), now.minusSeconds(30), "LIVE", "3800.25");
        saveQuote("TRENT", now.minusSeconds(3600), now.minusSeconds(3500), "LIVE", "6000.50");

        saveCandle("TCS", LocalDate.parse("2026-09-30"), "3700", "3900", "3600", "3800", 1000);
        saveCandle("TCS", LocalDate.parse("2026-10-01"), "3800", "4000", "3750", "3950", 1200);
        saveCandle("TCS", LocalDate.parse("2026-10-02"), "3950", "4025", "3900", "4000", 900);
    }

    @Test
    void requiresAuthenticationForMarketApi() throws Exception {
        mockMvc.perform(get("/api/v1/market/instruments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsAndLooksUpSupportedInstrumentsWithoutReturningEntities() throws Exception {
        mockMvc.perform(auth(get("/api/v1/market/instruments")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(80))
                .andExpect(jsonPath("$[0].symbol").exists())
                .andExpect(jsonPath("$[0].companyName").exists());

        mockMvc.perform(auth(get("/api/v1/market/instruments").param("query", "TCS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("TCS"));

        mockMvc.perform(auth(get("/api/v1/market/instruments/NSE/TCS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("TCS"))
                .andExpect(jsonPath("$.exchange").value("NSE"));
    }

    @Test
    void returnsOneAndBatchedPersistedQuotesWithHonestFreshness() throws Exception {
        mockMvc.perform(auth(get("/api/v1/market/quotes/NSE/TCS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastPrice").value(3800.25))
                .andExpect(jsonPath("$.providerUpdatedTimestamp").exists())
                .andExpect(jsonPath("$.receivedTimestamp").exists())
                .andExpect(jsonPath("$.dataStatus").value("LIVE"));

        mockMvc.perform(auth(get("/api/v1/market/quotes").param("symbols", "TCS,TRENT,RELIANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].symbol").value("TCS"))
                .andExpect(jsonPath("$[0].dataStatus").value("LIVE"))
                .andExpect(jsonPath("$[1].symbol").value("TRENT"))
                .andExpect(jsonPath("$[1].dataStatus").value("STALE"))
                .andExpect(jsonPath("$[2].symbol").value("RELIANCE"))
                .andExpect(jsonPath("$[2].dataStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$[2].lastPrice").value(org.hamcrest.Matchers.nullValue()));

        verify(quoteRepositorySpy, times(1))
                .findAllByInstrument_ExchangeAndInstrument_SymbolIn("NSE", List.of("TCS", "TRENT", "RELIANCE"));
        verifyNoInteractions(provider);
    }

    @Test
    void filtersAndBoundsDailyCandlesInTradingDateOrder() throws Exception {
        mockMvc.perform(auth(get("/api/v1/market/instruments/NSE/TCS/candles")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-02")
                        .param("limit", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("TCS"))
                .andExpect(jsonPath("$.resolution").value("1D"))
                .andExpect(jsonPath("$.candles.length()").value(2))
                .andExpect(jsonPath("$.candles[0].tradingDate").value("2026-10-01"))
                .andExpect(jsonPath("$.candles[1].tradingDate").value("2026-10-02"));

        mockMvc.perform(auth(get("/api/v1/market/instruments/NSE/TCS/candles")
                        .param("from", "2026-10-02")
                        .param("to", "2026-10-01")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(get("/api/v1/market/instruments/NSE/TCS/candles")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-02")
                        .param("limit", "501")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(get("/api/v1/market/instruments/NSE/TCS/candles")
                        .param("from", "not-a-date")
                        .param("to", "2026-10-02")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsEmptyBoundedHistoryAndRejectsUnsupportedSymbols() throws Exception {
        mockMvc.perform(auth(get("/api/v1/market/instruments/NSE/IRCTC/candles")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-02")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candles.length()").value(0));

        mockMvc.perform(auth(get("/api/v1/market/quotes/NSE/NOTREAL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/api/v1/market/quotes").param("symbols", "TCS,NOTREAL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/api/v1/market/instruments/NSE/NOTREAL")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(provider);
    }

    @Test
    void screensSupportedCompaniesUsingPersistedQuotesAndCandlesOnly() throws Exception {
        mockMvc.perform(get("/api/v1/market/screener"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(auth(get("/api/v1/market/screener").param("search", "tcs").param("limit", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("TCS"))
                .andExpect(jsonPath("$[0].freshnessStatus").value("LIVE"))
                .andExpect(jsonPath("$[0].fiftyTwoWeekHigh").value(4025))
                .andExpect(jsonPath("$[0].fiftyTwoWeekLow").value(3600))
                .andExpect(jsonPath("$[0].volatilityPercent").exists());

        mockMvc.perform(auth(get("/api/v1/market/screener").param("sort", "HIGHEST_VOLUME").param("limit", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].symbol").value("TCS"));

        mockMvc.perform(auth(get("/api/v1/market/screener").param("search", "NOTREAL")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(auth(get("/api/v1/market/screener").param("limit", "81")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(get("/api/v1/market/screener").param("sort", "UNKNOWN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(provider);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        String credentials = Base64.getEncoder().encodeToString(
                "market-api-test:market-api-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request.header("Authorization", "Basic " + credentials);
    }

    private void saveQuote(String symbol, Instant providerAt, Instant receivedAt, String dataStatus, String price) {
        Instrument instrument = instrumentRepository.findByExchangeAndSymbol("NSE", symbol).orElseThrow();
        var snapshot = new MarketQuoteSnapshot("NSE", symbol, null, receivedAt.minusSeconds(5),
                new BigDecimal("95"), new BigDecimal("110"), new BigDecimal("90"), null,
                new BigDecimal("94"), 1000L, new BigDecimal(price), "NSE_MCP_CM_MARKET", providerAt);
        quoteRepository.saveAndFlush(new MarketQuote(instrument, snapshot, receivedAt, "UNKNOWN", dataStatus));
    }

    private void saveCandle(String symbol, LocalDate date, String open, String high, String low,
            String close, long volume) {
        Instrument instrument = instrumentRepository.findByExchangeAndSymbol("NSE", symbol).orElseThrow();
        var snapshot = new MarketCandleSnapshot("NSE", symbol, null, date, null,
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
                volume, new BigDecimal(close), "NSE_MCP_BHAVCOPY", null);
        candleRepository.saveAndFlush(new MarketCandle(instrument, "1D",
                date.atStartOfDay(NSE_ZONE).toInstant(), snapshot, Instant.now()));
    }
}
