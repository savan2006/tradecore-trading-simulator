package com.tradecore.strategylab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.Instrument;
import com.tradecore.market.InstrumentRepository;
import com.tradecore.market.MarketCandle;
import com.tradecore.market.MarketCandleRepository;
import com.tradecore.market.MarketCandleSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-strategy-lab-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=strategy-test",
        "tradecore.security.password=strategy-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false",
        "tradecore.alerts.processing-interval=PT1H"
})
@AutoConfigureMockMvc
class StrategyLabApiTest {
    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private InstrumentRepository instruments;
    @Autowired private MarketCandleRepository candles;
    @Autowired private UserRegistrationService registration;
    private String credential;

    @BeforeEach
    void setup() {
        String email = "strategy-" + UUID.randomUUID() + "@example.invalid";
        registration.register(new RegistrationRequest(email, "Strategy-Test-Password-93!", "Strategy Test"));
        credential = "Basic " + Base64.getEncoder().encodeToString((email + ":Strategy-Test-Password-93!").getBytes(StandardCharsets.UTF_8));
        candles.deleteAll();
        Instrument tcs = instruments.findByExchangeAndSymbol("NSE", "TCS").orElseThrow();
        List<MarketCandle> history = new java.util.ArrayList<>();
        LocalDate start = LocalDate.now(NSE_ZONE).minusDays(70);
        for (int index = 0; index < 40; index++) {
            LocalDate date = start.plusDays(index);
            var snapshot = new MarketCandleSnapshot("NSE", "TCS", null, date, null,
                    new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
                    new BigDecimal("100"), 1_000L, new BigDecimal("100"), "TEST_FIXTURE", Instant.now());
            history.add(new MarketCandle(tcs, "1D", date.atStartOfDay(NSE_ZONE).toInstant(), snapshot, Instant.now()));
        }
        candles.saveAll(history);
        candles.flush();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/strategy-lab/backtests").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsDeterministicResultsWithoutChangingFinancialRows() throws Exception {
        String input = """
                {"symbol":"TCS","fromDate":"%s","toDate":"%s","strategy":"SIMPLE_MOVING_AVERAGE_CROSSOVER",
                 "startingCapital":10000,"fastPeriod":2,"slowPeriod":3}
                """.formatted(LocalDate.now(NSE_ZONE).minusDays(50), LocalDate.now(NSE_ZONE).minusDays(30));
        List<Long> before = financialCounts();
        MvcResult first = mvc.perform(post("/api/v1/strategy-lab/backtests").header("Authorization", credential)
                        .contentType("application/json").content(input))
                .andExpect(status().isOk()).andExpect(jsonPath("$.symbol").value("TCS"))
                .andExpect(jsonPath("$.numberOfTrades").value(0)).andReturn();
        MvcResult second = mvc.perform(post("/api/v1/strategy-lab/backtests").header("Authorization", credential)
                        .contentType("application/json").content(input))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstResult = mapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondResult = mapper.readTree(second.getResponse().getContentAsString());
        assertThat(firstResult).isEqualTo(secondResult);
        assertThat(financialCounts()).isEqualTo(before);
    }

    private List<Long> financialCounts() {
        return List.of(count("trading_account"), count("ledger_entry"), count("trading_order"),
                count("execution"), count("position"));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
