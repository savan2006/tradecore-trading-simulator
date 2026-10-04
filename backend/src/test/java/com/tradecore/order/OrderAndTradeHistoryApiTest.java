package com.tradecore.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradecore.execution.OrderExecutionService;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import com.tradecore.market.MarketHoursPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-history-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=history-test",
        "tradecore.security.password=history-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class OrderAndTradeHistoryApiTest {
    private static final String PASSWORD = "History-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderPlacementService placement;
    @Autowired private OrderCancellationService cancellation;
    @Autowired private OrderExecutionService execution;
    @MockitoSpyBean private MarketHoursPolicy marketHours;
    @MockitoSpyBean private MarketDataProvider provider;
    private UUID tcsId;
    private UUID infyId;

    @BeforeEach
    void setUpQuotes() {
        tcsId = instrumentId("TCS");
        infyId = instrumentId("INFY");
        putQuote(tcsId, "100");
        putQuote(infyId, "200");
        doReturn(true).when(marketHours).isRegularSession(any(Instant.class));
        clearInvocations(provider);
    }

    @Test
    void ordersAndTradesAreScopedToAuthenticatedUser() throws Exception {
        Account owner = account(), other = account();
        UUID ownOrder = place(owner, "TCS", "DELIVERY");
        UUID otherOrder = place(other, "INFY", "INTRADAY");

        orders(owner).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(ownOrder.toString()))
                .andExpect(jsonPath("$.content[0].symbol").value("TCS"));
        assertThat(otherOrder).isNotEqualTo(ownOrder);
        trades(owner).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void ownTradeHistoryIncludesPersistedExecutionFields() throws Exception {
        Account owner = account(); UUID order = place(owner, "TCS", "DELIVERY");
        assertThat(execution.executePending(order)).isTrue();

        trades(owner).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(order.toString()))
                .andExpect(jsonPath("$.content[0].symbol").value("TCS"))
                .andExpect(jsonPath("$.content[0].side").value("BUY"))
                .andExpect(jsonPath("$.content[0].tradingMode").value("DELIVERY"))
                .andExpect(jsonPath("$.content[0].executedQuantity").value(2))
                .andExpect(jsonPath("$.content[0].executionPrice").value(100.0))
                .andExpect(jsonPath("$.content[0].executedAt").isNotEmpty());
    }

    @Test
    void orderFiltersApplyStatusSymbolModeAndDateRange() throws Exception {
        Account owner = account();
        UUID tcsDelivery = place(owner, "TCS", "DELIVERY");
        UUID infyIntraday = place(owner, "INFY", "INTRADAY");
        Instant old = Instant.parse("2026-01-01T10:00:00Z");
        Instant inRange = Instant.parse("2026-01-02T10:00:00Z");
        setCreated(tcsDelivery, old);
        setCreated(infyIntraday, inRange);

        orders(owner, "status", "pending", "symbol", "iNfY", "tradingMode", "intraday",
                "from", "2026-01-02T00:00:00Z", "to", "2026-01-02T23:59:59Z")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(infyIntraday.toString()))
                .andExpect(jsonPath("$.content[0].symbol").value("INFY"));
        assertThat(tcsDelivery).isNotEqualTo(infyIntraday);
    }

    @Test
    void optionalDateFiltersWorkIndependentlyAndTogether() throws Exception {
        Account owner = account();
        UUID january = place(owner, "TCS", "DELIVERY");
        UUID february = place(owner, "INFY", "DELIVERY");
        UUID march = place(owner, "TCS", "INTRADAY");
        setCreated(january, Instant.parse("2026-01-15T12:00:00Z"));
        setCreated(february, Instant.parse("2026-02-15T12:00:00Z"));
        setCreated(march, Instant.parse("2026-03-15T12:00:00Z"));

        orders(owner).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        orders(owner, "from", "2026-02-01")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        orders(owner, "to", "2026-02-28")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        orders(owner, "from", "2026-02-01", "to", "2026-02-28")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(february.toString()));
    }

    @Test
    void orderPaginationIsBoundedStableAndNewestFirst() throws Exception {
        Account owner = account();
        UUID first = place(owner, "TCS", "DELIVERY");
        UUID second = place(owner, "INFY", "INTRADAY");
        UUID third = place(owner, "TCS", "DELIVERY");
        Instant base = Instant.parse("2026-02-01T10:00:00Z");
        setCreated(first, base); setCreated(second, base.plusSeconds(1)); setCreated(third, base.plusSeconds(2));

        orders(owner, "page", "0", "size", "1").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(third.toString()))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        orders(owner, "page", "1", "size", "1").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(second.toString()));
        orders(owner, "size", "101").andExpect(status().isBadRequest());
    }

    @Test
    void orderDetailIsAvailableOnlyToOwner() throws Exception {
        Account owner = account(), other = account(); UUID order = place(owner, "TCS", "DELIVERY");

        mvc.perform(get("/api/v1/orders/{id}", order).header("Authorization", basic(owner.email)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(order.toString()));
        mvc.perform(get("/api/v1/orders/{id}", order).header("Authorization", basic(other.email)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelledAndFilledOrdersAppearWithTheirCurrentStates() throws Exception {
        Account owner = account();
        UUID cancelled = place(owner, "TCS", "DELIVERY");
        putQuote(infyId, "100"); // Satisfy this BUY limit before exercising execution.
        UUID filled = place(owner, "INFY", "INTRADAY");
        cancellation.cancel(owner.email, cancelled);
        assertThat(execution.executePending(filled)).isTrue();

        orders(owner, "status", "CANCELLED").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(cancelled.toString()));
        orders(owner, "status", "FILLED").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(filled.toString()));
        trades(owner).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(filled.toString()));
    }

    @Test
    void unauthenticatedHistoryRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/trades")).andExpect(status().isUnauthorized());
    }

    @Test
    void historyReadsDoNotMutateFinancialStateOrCallMarketProvider() throws Exception {
        Account owner = account(); UUID order = place(owner, "TCS", "DELIVERY");
        var accountBefore = jdbc.queryForMap("select available_balance,reserved_balance,updated_at,version from trading_account where id=?", owner.id);
        int ledgerBefore = jdbc.queryForObject("select count(*) from ledger_entry where account_id=?", Integer.class, owner.id);
        int executionsBefore = jdbc.queryForObject("select count(*) from execution where account_id=?", Integer.class, owner.id);
        clearInvocations(provider);

        orders(owner).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/{id}", order).header("Authorization", basic(owner.email))).andExpect(status().isOk());
        trades(owner).andExpect(status().isOk());

        assertThat(jdbc.queryForMap("select available_balance,reserved_balance,updated_at,version from trading_account where id=?", owner.id)).isEqualTo(accountBefore);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where account_id=?", Integer.class, owner.id)).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForObject("select count(*) from execution where account_id=?", Integer.class, owner.id)).isEqualTo(executionsBefore);
        verifyNoInteractions(provider);
    }

    private org.springframework.test.web.servlet.ResultActions orders(Account account, String... params) throws Exception {
        var request = get("/api/v1/orders").header("Authorization", basic(account.email));
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return mvc.perform(request);
    }
    private org.springframework.test.web.servlet.ResultActions trades(Account account) throws Exception {
        return mvc.perform(get("/api/v1/trades").header("Authorization", basic(account.email)));
    }
    private UUID place(Account account, String symbol, String mode) {
        OrderPlacementResponse result = placement.placeOrder(account.email,
                new OrderPlacementRequest("NSE", symbol, "BUY", "LIMIT", mode, 2, new BigDecimal("120")), null);
        return result.orderId();
    }
    private void setCreated(UUID order, Instant createdAt) {
        jdbc.update("update trading_order set created_at=?,updated_at=? where id=?", Timestamp.from(createdAt), Timestamp.from(createdAt), order);
    }
    private Account account() {
        String email = "history-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse result = registration.register(new RegistrationRequest(email, PASSWORD, "History Test"));
        return new Account(email, result.accountId());
    }
    private UUID instrumentId(String symbol) {
        return jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol=?", UUID.class, symbol);
    }
    private void putQuote(UUID instrument, String price) {
        jdbc.update("delete from market_quote where instrument_id=?", instrument);
        Instant now = Instant.now().minusSeconds(1);
        jdbc.update("insert into market_quote (id,instrument_id,last_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,'OPEN','LIVE')",
                UUID.randomUUID(), instrument, new BigDecimal(price), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }
    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
    private record Account(String email, UUID id) { }
}
