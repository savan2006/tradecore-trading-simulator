package com.tradecore.learning;

import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-learning-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=learning-test",
        "tradecore.security.password=learning-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false",
        "tradecore.alerts.processing-interval=PT1H"
})
@AutoConfigureMockMvc
class LearningProfileApiTest {
    private static final String PASSWORD = "Learning-Test-Password-93!";
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRegistrationService registration;
    @MockitoSpyBean private MarketDataProvider provider;
    private String email;

    @BeforeEach
    void setUp() {
        email = registration.register(new RegistrationRequest("learning-" + UUID.randomUUID() + "@example.invalid",
                PASSWORD, "Learning Test")).email();
        UUID instrumentId = instrumentId("TCS");
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        jdbc.update("delete from market_candle where instrument_id=?", instrumentId);
        clearInvocations(provider);
    }

    @Test
    void returnsSeededSupportedCompanyProfileAndProfileList() throws Exception {
        mvc.perform(get("/api/v1/learning/companies/TCS").header("Authorization", basic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("TCS"))
                .andExpect(jsonPath("$.companyName").value("Tata Consultancy Services Limited"))
                .andExpect(jsonPath("$.sector").value("Information Technology"))
                .andExpect(jsonPath("$.businessType").value("IT services and consulting"))
                .andExpect(jsonPath("$.majorBusinessFactors.length()").value(4))
                .andExpect(jsonPath("$.commonPriceDrivers.length()").value(4))
                .andExpect(jsonPath("$.importantRisks.length()").value(4))
                .andExpect(jsonPath("$.educationalObservations.length()").value(3));
        String listBody = mvc.perform(get("/api/v1/learning/companies").header("Authorization", basic()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(80))
                .andReturn().getResponse().getContentAsString();
        JsonNode profiles = objectMapper.readTree(listBody);
        assertThat(profiles.findValuesAsText("symbol")).contains("HDFCBANK", "RELIANCE", "TCS", "SBIN",
                "INFY", "MARUTI", "HINDUNILVR", "ONGC", "BHARTIARTL", "SUNPHARMA", "LT", "TATASTEEL",
                "ULTRACEMCO", "ASIANPAINT", "DLF", "TITAN", "INDIGO", "ADANIPORTS", "KPRMILL", "SUNTV",
                "RVNL", "IRCTC");
        assertThat(profiles.findValuesAsText("sector")).contains("Financial Services", "Information Technology",
                "Automobiles", "Consumer Staples", "Energy", "Telecommunications", "Healthcare", "Industrials",
                "Materials", "Real Estate", "Consumer Discretionary", "Travel and Transportation",
                "Transport and Infrastructure", "Textiles and Apparel", "Media and Entertainment");
        verifyNoInteractions(provider);
    }

    @Test
    void unsupportedCompanyReturnsNotFoundAndReadDoesNotMutateFinancialState() throws Exception {
        List<Long> before = domainTableCounts();
        mvc.perform(get("/api/v1/learning/companies/NOTSUPPORTED").header("Authorization", basic()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/learning/companies").header("Authorization", basic()))
                .andExpect(status().isOk());
        assertThat(domainTableCounts()).isEqualTo(before);
        verifyNoInteractions(provider);
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/learning/companies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/learning/companies/TCS")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/learning/companies/TCS/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void overviewMapsProfileLiveQuoteRecentCandlesAndPriceChangesWithoutMutation() throws Exception {
        UUID instrumentId = instrumentId("TCS");
        putQuote(instrumentId, "110", "100", Instant.now());
        putCandles(instrumentId);
        List<Long> before = domainTableCounts();

        mvc.perform(get("/api/v1/learning/companies/TCS/overview").param("limit", "2")
                        .header("Authorization", basic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learningProfile.symbol").value("TCS"))
                .andExpect(jsonPath("$.learningProfile.companyName").value("Tata Consultancy Services Limited"))
                .andExpect(jsonPath("$.latestQuote.lastPrice").value(110.0))
                .andExpect(jsonPath("$.quoteStatus").value("LIVE"))
                .andExpect(jsonPath("$.latestQuoteChange.absolute").value(10.0))
                .andExpect(jsonPath("$.latestQuoteChange.percent").value(10.0))
                .andExpect(jsonPath("$.recentDailyCandles.length()").value(2))
                .andExpect(jsonPath("$.recentDailyCandles[0].close").value(100.0))
                .andExpect(jsonPath("$.recentDailyCandles[1].close").value(108.0))
                .andExpect(jsonPath("$.candlePeriodChange.absolute").value(8.0))
                .andExpect(jsonPath("$.candlePeriodChange.percent").value(8.0));

        mvc.perform(get("/api/v1/learning/companies/TCS/overview").param("limit", "91")
                        .header("Authorization", basic()))
                .andExpect(status().isBadRequest());
        assertThat(domainTableCounts()).isEqualTo(before);
        verifyNoInteractions(provider);
    }

    @Test
    void overviewMarksStaleAndMissingQuotesClearly() throws Exception {
        UUID instrumentId = instrumentId("TCS");
        putQuote(instrumentId, "110", "100", Instant.now().minusSeconds(700));
        putCandles(instrumentId);
        mvc.perform(get("/api/v1/learning/companies/TCS/overview").header("Authorization", basic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteStatus").value("STALE"))
                .andExpect(jsonPath("$.latestQuote.dataStatus").value("STALE"))
                .andExpect(jsonPath("$.latestQuoteChange").doesNotExist())
                .andExpect(jsonPath("$.recentDailyCandles.length()").value(3));

        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        mvc.perform(get("/api/v1/learning/companies/TCS/overview").header("Authorization", basic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.latestQuote.dataStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.latestQuote.lastPrice").doesNotExist())
                .andExpect(jsonPath("$.latestQuoteChange").doesNotExist());
        verifyNoInteractions(provider);
    }

    @Test
    void overviewRejectsUnsupportedAndUnprofiledSymbols() throws Exception {
        mvc.perform(get("/api/v1/learning/companies/NOTSUPPORTED/overview").header("Authorization", basic()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/learning/companies/IRCTC/overview").header("Authorization", basic()))
                .andExpect(status().isOk());
        verifyNoInteractions(provider);
    }

    private List<Long> domainTableCounts() {
        return List.of(count("trading_account"), count("ledger_entry"), count("trading_order"),
                count("execution"), count("position"), count("market_quote"), count("market_candle"));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private UUID instrumentId(String symbol) {
        return jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol=?", UUID.class, symbol);
    }

    private void putQuote(UUID instrumentId, String last, String previousClose, Instant updatedAt) {
        Instant receivedAt = Instant.now();
        jdbc.update("insert into market_quote (id,instrument_id,last_price,previous_close,open_price,high_price,low_price," +
                        "volume,market_at,provider_updated_at,received_at,market_status,data_status) " +
                        "values (?,?,?,?,?,?,?,?,?,? ,?,'OPEN','LIVE')",
                UUID.randomUUID(), instrumentId, new BigDecimal(last), new BigDecimal(previousClose),
                new BigDecimal("105"), new BigDecimal("112"), new BigDecimal("99"), 1000L,
                Timestamp.from(updatedAt), Timestamp.from(updatedAt), Timestamp.from(receivedAt));
    }

    private void putCandles(UUID instrumentId) {
        putCandle(instrumentId, LocalDate.now(EXCHANGE_ZONE).minusDays(3), "90", "95");
        putCandle(instrumentId, LocalDate.now(EXCHANGE_ZONE).minusDays(2), "96", "100");
        putCandle(instrumentId, LocalDate.now(EXCHANGE_ZONE).minusDays(1), "101", "108");
    }

    private void putCandle(UUID instrumentId, LocalDate date, String open, String close) {
        Instant bucket = date.atStartOfDay(EXCHANGE_ZONE).toInstant();
        jdbc.update("insert into market_candle (id,instrument_id,resolution,bucket_start,open_price,high_price,low_price," +
                        "close_price,volume,created_at) values (?,?, '1D',?,?,?,?,?,?,?)",
                UUID.randomUUID(), instrumentId, Timestamp.from(bucket), new BigDecimal(open),
                new BigDecimal(close), new BigDecimal(open), new BigDecimal(close), 100L,
                Timestamp.from(Instant.now()));
    }

    private String basic() {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
