package com.tradecore.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-performance-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=performance-test",
        "tradecore.security.password=performance-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class PerformanceApiTest {
    private static final String PASSWORD = "Performance-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @MockitoSpyBean private MarketDataProvider provider;

    @BeforeEach
    void clearQuotesAndProviderCalls() {
        jdbc.update("delete from market_quote");
        clearInvocations(provider);
    }

    @Test
    void calculatesOrderExecutionPnlAndClosedPositionStatisticsDeterministically() throws Exception {
        Account account = account();
        UUID tcs = instrumentId("TCS"), infy = instrumentId("INFY"), hdfc = instrumentId("HDFCBANK");
        putQuote(tcs, "110", Instant.now(), "LIVE");
        seedPosition(account.id, tcs, "DELIVERY", 2, "100", "40");
        seedPosition(account.id, infy, "DELIVERY", 0, "0", "25");
        seedPosition(account.id, hdfc, "INTRADAY", 0, "0", "-10");
        seedOrder(account.id, tcs, "BUY", "DELIVERY", "FILLED", 2, 0, true);
        seedOrder(account.id, tcs, "SELL", "DELIVERY", "FILLED", 1, 0, true);
        seedOrder(account.id, infy, "BUY", "INTRADAY", "CANCELLED", 1, 1, false);
        seedOrder(account.id, hdfc, "SELL", "DELIVERY", "PENDING", 1, 1, false);

        JsonNode response = body(account);
        assertThat(response.path("totalOrders").asLong()).isEqualTo(4);
        assertThat(response.path("filledOrders").asLong()).isEqualTo(2);
        assertThat(response.path("cancelledOrders").asLong()).isEqualTo(1);
        assertThat(response.path("totalExecutions").asLong()).isEqualTo(3);
        assertThat(response.path("currentOpenPositions").asInt()).isEqualTo(1);
        assertThat(response.path("realizedPnl").decimalValue()).isEqualByComparingTo("55.0000");
        assertThat(response.path("unrealizedPnl").decimalValue()).isEqualByComparingTo("20.0000");
        assertThat(response.path("totalPnl").decimalValue()).isEqualByComparingTo("75.0000");
        assertThat(response.path("currentPortfolioValue").decimalValue()).isEqualByComparingTo("220.0000");
        assertThat(response.path("buyOrders").asLong()).isEqualTo(2);
        assertThat(response.path("sellOrders").asLong()).isEqualTo(2);
        assertThat(response.path("deliveryOrders").asLong()).isEqualTo(3);
        assertThat(response.path("intradayOrders").asLong()).isEqualTo(1);
        assertThat(response.path("profitableClosedPositions").asLong()).isEqualTo(1);
        assertThat(response.path("losingClosedPositions").asLong()).isEqualTo(1);
        assertThat(response.path("bestRealizedPosition").path("symbol").asText()).isEqualTo("INFY");
        assertThat(response.path("worstRealizedPosition").path("symbol").asText()).isEqualTo("HDFCBANK");
        assertThat(response.path("recentPerformance").size()).isEqualTo(2);
        assertThat(response.path("recentPerformance").get(0).path("symbol").asText()).isIn("INFY", "HDFCBANK");
        verifyNoInteractions(provider);
    }

    @Test
    void emptyAccountReturnsZeroStatisticsAndEmptyChart() throws Exception {
        JsonNode response = body(account());
        assertThat(response.path("totalOrders").asLong()).isZero();
        assertThat(response.path("totalExecutions").asLong()).isZero();
        assertThat(response.path("currentOpenPositions").asInt()).isZero();
        assertThat(response.path("realizedPnl").decimalValue()).isEqualByComparingTo("0.0000");
        assertThat(response.path("totalPnl").decimalValue()).isEqualByComparingTo("0.0000");
        assertThat(response.path("valuationStatus").asText()).isEqualTo("EMPTY");
        assertThat(response.path("recentPerformance")).isEmpty();
    }

    @Test
    void staleOrMissingQuotesDoNotInventUnrealizedOrTotalPnl() throws Exception {
        Account stale = account();
        UUID tcs = instrumentId("TCS");
        seedPosition(stale.id, tcs, "DELIVERY", 2, "100", "8");
        putQuote(tcs, "120", Instant.now().minusSeconds(601), "LIVE");
        JsonNode staleResult = body(stale);
        assertThat(staleResult.path("valuationStatus").asText()).isEqualTo("STALE");
        assertThat(staleResult.path("realizedPnl").decimalValue()).isEqualByComparingTo("8.0000");
        assertThat(staleResult.path("unrealizedPnl").isNull()).isTrue();
        assertThat(staleResult.path("currentPortfolioValue").isNull()).isTrue();
        assertThat(staleResult.path("totalPnl").isNull()).isTrue();

        jdbc.update("delete from market_quote where instrument_id=?", tcs);
        Account missing = account();
        seedPosition(missing.id, tcs, "DELIVERY", 1, "100", "0");
        JsonNode missingResult = body(missing);
        assertThat(missingResult.path("valuationStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(missingResult.path("unrealizedPnl").isNull()).isTrue();
        assertThat(missingResult.path("totalPnl").isNull()).isTrue();
        verifyNoInteractions(provider);
    }

    @Test
    void statisticsAreScopedToTheAuthenticatedUsersAccount() throws Exception {
        Account owner = account(), other = account();
        seedPosition(other.id, instrumentId("TCS"), "DELIVERY", 3, "100", "15");
        seedOrder(other.id, instrumentId("TCS"), "BUY", "DELIVERY", "FILLED", 1, 0, true);

        JsonNode response = body(owner);
        assertThat(response.path("totalOrders").asLong()).isZero();
        assertThat(response.path("totalExecutions").asLong()).isZero();
        assertThat(response.path("realizedPnl").decimalValue()).isEqualByComparingTo("0.0000");
        assertThat(response.path("currentOpenPositions").asInt()).isZero();
    }

    @Test
    void endpointIsAuthenticatedAndReadDoesNotMutateFinancialStateOrCallProvider() throws Exception {
        mvc.perform(get("/api/v1/performance/me")).andExpect(status().isUnauthorized());
        Account account = account();
        UUID tcs = instrumentId("TCS");
        seedPosition(account.id, tcs, "DELIVERY", 2, "100", "5");
        seedOrder(account.id, tcs, "BUY", "DELIVERY", "FILLED", 2, 0, true);
        var accountBefore = jdbc.queryForMap("select available_balance,reserved_balance,version from trading_account where id=?", account.id);
        var positionBefore = jdbc.queryForMap("select quantity,reserved_quantity,average_price,realized_pnl,version from position where account_id=?", account.id);
        int ledgerBefore = jdbc.queryForObject("select count(*) from ledger_entry where account_id=?", Integer.class, account.id);

        body(account);

        assertThat(jdbc.queryForMap("select available_balance,reserved_balance,version from trading_account where id=?", account.id)).isEqualTo(accountBefore);
        assertThat(jdbc.queryForMap("select quantity,reserved_quantity,average_price,realized_pnl,version from position where account_id=?", account.id)).isEqualTo(positionBefore);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where account_id=?", Integer.class, account.id)).isEqualTo(ledgerBefore);
        verifyNoInteractions(provider);
    }

    private JsonNode body(Account account) throws Exception {
        String result = mvc.perform(get("/api/v1/performance/me").header("Authorization", basic(account.email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(result);
    }
    private Account account() {
        String email = "performance-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse result = registration.register(new RegistrationRequest(email, PASSWORD, "Performance Test"));
        return new Account(email, result.accountId());
    }
    private UUID instrumentId(String symbol) {
        return jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol=?", UUID.class, symbol);
    }
    private void seedPosition(UUID account, UUID instrument, String mode, long quantity, String average, String realized) {
        jdbc.update("insert into position (id,account_id,instrument_id,trading_mode,quantity,reserved_quantity,average_price,realized_pnl,updated_at,version) values (?,?,?,?,?,0,?,?,?,0)",
                UUID.randomUUID(), account, instrument, mode, quantity, new BigDecimal(average), new BigDecimal(realized), Timestamp.from(Instant.now()));
    }
    private void seedOrder(UUID account, UUID instrument, String side, String mode, String state,
            long requested, long remaining, boolean withExecutions) {
        UUID order = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into trading_order (id,account_id,instrument_id,side,order_type,trading_mode,requested_quantity,executed_quantity,remaining_quantity,limit_price,reserved_amount,status,created_at,updated_at,version) values (?,?,?,?,'MARKET',?,?,?,?,null,0,?,?,?,0)",
                order, account, instrument, side, mode, requested, requested - remaining, remaining, state,
                Timestamp.from(now), Timestamp.from(now));
        if (withExecutions) {
            int count = side.equals("BUY") ? 2 : 1;
            for (int index = 0; index < count; index++) {
                jdbc.update("insert into execution (id,account_id,order_id,instrument_id,side,trading_mode,quantity,price,executed_at,fee) values (?,?,?,?,?,?,?,?,?,0)",
                        UUID.randomUUID(), account, order, instrument, side, mode, 1L, new BigDecimal("100.000000"), Timestamp.from(now.plusMillis(index)));
            }
        }
    }
    private void putQuote(UUID instrument, String price, Instant providerTime, String dataStatus) {
        Instant now = Instant.now();
        jdbc.update("insert into market_quote (id,instrument_id,last_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,'OPEN',?)",
                UUID.randomUUID(), instrument, new BigDecimal(price), Timestamp.from(providerTime),
                Timestamp.from(providerTime), Timestamp.from(now), dataStatus);
    }
    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
    private record Account(String email, UUID id) { }
}
