package com.tradecore.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketHoursPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-orders-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "tradecore.security.user=order-static-test",
        "tradecore.security.password=order-static-password"
})
@AutoConfigureMockMvc
class OrderPlacementApiTest {
    private static final String PASSWORD = "order-test-password-9";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRegistrationService registrationService;
    @Autowired private OrderPlacementService orderPlacementService;

    @MockitoSpyBean private TradingOrderRepository orderRepository;
    @MockitoSpyBean private MarketHoursPolicy marketHoursPolicy;

    private UUID instrumentId;

    @BeforeEach
    void prepareFreshTcsQuote() {
        instrumentId = jdbcTemplate.queryForObject(
                "SELECT id FROM instrument WHERE exchange = 'NSE' AND symbol = 'TCS'", UUID.class);
        putQuote(Instant.now(), "OPEN");
    }

    @Test
    void validBuyCreatesPendingOrderEventAndReservesFundsWithoutSettlement() throws Exception {
        TestAccount user = createAccount();

        MvcResult result = place(user, request("BUY", "LIMIT", "DELIVERY", 2, "100.00"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("TCS"))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedQuantity").value(2))
                .andExpect(jsonPath("$.executedQuantity").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(2))
                .andReturn();
        UUID orderId = orderId(result);

        assertBalance(user.accountId(), "99800.0000", "200.0000");
        assertThat(count("SELECT COUNT(*) FROM order_event WHERE order_id = ?", orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT new_state FROM order_event WHERE order_id = ?", String.class, orderId))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT event_type FROM order_event WHERE order_id = ?", String.class, orderId))
                .isEqualTo("ORDER_PLACED");
        assertThat(count("SELECT COUNT(*) FROM execution WHERE order_id = ?", orderId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM position WHERE account_id = ?", user.accountId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger_entry WHERE account_id = ?", user.accountId())).isEqualTo(1);
    }

    @Test
    void validSellReservesOnlySellableQuantity() throws Exception {
        TestAccount user = createAccount();
        seedPosition(user.accountId(), "DELIVERY", 7, 2);

        MvcResult result = place(user, request("SELL", "LIMIT", "DELIVERY", 5, "100.00"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.executedQuantity").value(0))
                .andReturn();

        UUID orderId = orderId(result);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM position WHERE account_id = ?", Long.class, user.accountId()))
                .isEqualTo(7L);
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_quantity FROM position WHERE account_id = ?", Long.class, user.accountId()))
                .isEqualTo(7L);
        assertThat(count("SELECT COUNT(*) FROM execution WHERE order_id = ?", orderId)).isZero();
        assertBalance(user.accountId(), "100000.0000", "0.0000");
    }

    @Test
    void validMarketBuyUsesFreshQuoteAndRequiresOpenMarket() throws Exception {
        TestAccount user = createAccount();
        doReturn(true).when(marketHoursPolicy).isRegularSession(any(Instant.class));

        place(user, request("BUY", "MARKET", "INTRADAY", 2, null), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.limitPrice").doesNotExist());
        assertBalance(user.accountId(), "99750.0000", "250.0000");
    }

    @Test
    void invalidQuantityIsRejectedWithoutReservation() throws Exception {
        TestAccount user = createAccount();
        place(user, request("BUY", "LIMIT", "DELIVERY", 0, "100"), null)
                .andExpect(status().isBadRequest());
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void invalidPriceIsRejectedWithoutReservation() throws Exception {
        TestAccount user = createAccount();
        place(user, request("BUY", "LIMIT", "DELIVERY", 1, "-1"), null)
                .andExpect(status().isBadRequest());
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void unsupportedInstrumentIsRejected() throws Exception {
        TestAccount user = createAccount();
        place(user, new OrderPlacementRequest("NSE", "NOTSUPPORTED", "BUY", "LIMIT", "DELIVERY", 1,
                new BigDecimal("100")), null).andExpect(status().isNotFound());
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void missingQuoteIsRejected() throws Exception {
        TestAccount user = createAccount();
        jdbcTemplate.update("DELETE FROM market_quote WHERE instrument_id = ?", instrumentId);
        place(user, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isUnprocessableEntity());
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void staleQuoteIsRejected() throws Exception {
        TestAccount user = createAccount();
        putQuote(Instant.now().minusSeconds(601), "OPEN");
        place(user, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isUnprocessableEntity());
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void insufficientBuyFundsIsRejectedWithoutPartialReservation() throws Exception {
        TestAccount user = createAccount();
        jdbcTemplate.update("UPDATE trading_account SET available_balance = 1000 WHERE id = ?", user.accountId());
        place(user, request("BUY", "LIMIT", "DELIVERY", 11, "100"), null)
                .andExpect(status().isUnprocessableEntity());
        assertBalance(user.accountId(), "1000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void insufficientSellQuantityIsRejectedWithoutReservation() throws Exception {
        TestAccount user = createAccount();
        seedPosition(user.accountId(), "DELIVERY", 5, 1);
        place(user, request("SELL", "LIMIT", "DELIVERY", 5, "100"), null)
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_quantity FROM position WHERE account_id = ?", Long.class, user.accountId()))
                .isEqualTo(1L);
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void inactiveAccountCannotPlaceOrders() throws Exception {
        TestAccount user = createAccount();
        jdbcTemplate.update("UPDATE trading_account SET status = 'CLOSED' WHERE id = ?", user.accountId());
        place(user, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isConflict());
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void idempotencyReplaysSameOrderAndRejectsChangedRequest() throws Exception {
        TestAccount user = createAccount();
        String key = UUID.randomUUID().toString();
        MvcResult first = place(user, request("BUY", "LIMIT", "DELIVERY", 2, "100"), key)
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = place(user, request("BUY", "LIMIT", "DELIVERY", 2, "100"), key)
                .andExpect(status().isCreated()).andReturn();
        assertThat(orderId(replay)).isEqualTo(orderId(first));
        place(user, request("BUY", "LIMIT", "DELIVERY", 3, "100"), key)
                .andExpect(status().isConflict());
        assertThat(orderCount(user.accountId())).isEqualTo(1);
        assertBalance(user.accountId(), "99800.0000", "200.0000");
        assertThat(count("SELECT COUNT(*) FROM idempotency_record WHERE account_id = ?", user.accountId())).isEqualTo(1);
    }

    @Test
    void applicableRiskLimitRejectsOversizedOrder() throws Exception {
        TestAccount user = createAccount();
        jdbcTemplate.update("INSERT INTO risk_limit (id, account_id, scope, limit_type, limit_value, enabled, created_at) "
                        + "VALUES (?, ?, 'ACCOUNT', 'MAX_ORDER_AMOUNT', 150, TRUE, ?)",
                UUID.randomUUID(), user.accountId(), Timestamp.from(Instant.now()));
        place(user, request("BUY", "LIMIT", "DELIVERY", 2, "100"), null)
                .andExpect(status().isUnprocessableEntity());
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void orderCreationFailureRollsBackReservationAndLeavesNoOrphanEvent() throws Exception {
        TestAccount user = createAccount();
        doThrow(new IllegalStateException("simulated order persistence failure"))
                .when(orderRepository).saveAndFlush(any(TradingOrder.class));

        assertThatThrownBy(() -> orderPlacementService.placeOrder(user.email(),
                request("BUY", "LIMIT", "DELIVERY", 2, "100"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated order persistence failure");
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM order_event e JOIN trading_order o ON o.id = e.order_id WHERE o.account_id = ?",
                user.accountId())).isZero();
    }

    @Test
    void concurrentBuyRequestsCannotOverspendAvailableFunds() throws Exception {
        TestAccount user = createAccount();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> concurrentPlace(user, ready, start));
            Future<Integer> second = executor.submit(() -> concurrentPlace(user, ready, start));
            ready.await();
            start.countDown();
            int a = first.get();
            int b = second.get();
            assertThat(java.util.List.of(a, b)).containsExactlyInAnyOrder(201, 422);
        } finally {
            executor.shutdownNow();
        }
        assertThat(orderCount(user.accountId())).isEqualTo(1);
        assertBalance(user.accountId(), "40000.0000", "60000.0000");
    }

    @Test
    void concurrentSellRequestsCannotOversellPosition() throws Exception {
        TestAccount user = createAccount();
        seedPosition(user.accountId(), "DELIVERY", 100, 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> concurrentSell(user, ready, start));
            Future<Integer> second = executor.submit(() -> concurrentSell(user, ready, start));
            ready.await();
            start.countDown();
            int a = first.get();
            int b = second.get();
            assertThat(java.util.List.of(a, b)).containsExactlyInAnyOrder(201, 422);
        } finally {
            executor.shutdownNow();
        }
        assertThat(orderCount(user.accountId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM position WHERE account_id = ?", Long.class, user.accountId()))
                .isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_quantity FROM position WHERE account_id = ?", Long.class, user.accountId()))
                .isEqualTo(70L);
    }

    private int concurrentPlace(TestAccount user, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return place(user, request("BUY", "LIMIT", "DELIVERY", 60, "1000"), UUID.randomUUID().toString())
                .andReturn().getResponse().getStatus();
    }

    private int concurrentSell(TestAccount user, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return place(user, request("SELL", "LIMIT", "DELIVERY", 70, "100"), UUID.randomUUID().toString())
                .andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions place(TestAccount user,
            OrderPlacementRequest request, String idempotencyKey) throws Exception {
        var builder = post("/api/v1/orders").header("Authorization", basic(user.email(), PASSWORD))
                .contentType("application/json").content(objectMapper.writeValueAsBytes(request));
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return mockMvc.perform(builder);
    }

    private TestAccount createAccount() {
        String email = "order-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse response = registrationService.register(
                new RegistrationRequest(email, PASSWORD, "Synthetic Order Test"));
        return new TestAccount(email, response.accountId());
    }

    private void putQuote(Instant providerUpdatedAt, String marketStatus) {
        jdbcTemplate.update("DELETE FROM market_quote WHERE instrument_id = ?", instrumentId);
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO market_quote (id, instrument_id, last_price, market_at, provider_updated_at, "
                        + "received_at, market_status, data_status) VALUES (?, ?, ?, ?, ?, ?, ?, 'LIVE')",
                UUID.randomUUID(), instrumentId, new BigDecimal("125.00"), Timestamp.from(providerUpdatedAt),
                Timestamp.from(providerUpdatedAt), Timestamp.from(now), marketStatus);
    }

    private void seedPosition(UUID accountId, String mode, long quantity, long reserved) {
        jdbcTemplate.update("INSERT INTO position (id, account_id, instrument_id, trading_mode, quantity, reserved_quantity, "
                        + "average_price, realized_pnl, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, 100, 0, ?, 0)",
                UUID.randomUUID(), accountId, instrumentId, mode, quantity, reserved, Timestamp.from(Instant.now()));
    }

    private void assertBalance(UUID accountId, String available, String reserved) {
        assertThat(jdbcTemplate.queryForObject("SELECT available_balance FROM trading_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo(available);
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_balance FROM trading_account WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo(reserved);
    }

    private int orderCount(UUID accountId) {
        return count("SELECT COUNT(*) FROM trading_order WHERE account_id = ?", accountId);
    }

    private int count(String sql, Object arg) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arg);
    }

    private UUID orderId(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.path("orderId").asText());
    }

    private static OrderPlacementRequest request(String side, String type, String mode, long quantity, String price) {
        return new OrderPlacementRequest("NSE", "TCS", side, type, mode, quantity,
                price == null ? null : new BigDecimal(price));
    }

    private static String basic(String email, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
    }

    private record TestAccount(String email, UUID accountId) { }
}
