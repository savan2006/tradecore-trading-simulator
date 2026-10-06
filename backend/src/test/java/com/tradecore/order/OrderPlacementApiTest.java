package com.tradecore.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.execution.OrderExecutionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    @Autowired private OrderExecutionService orderExecutionService;

    @MockitoSpyBean private TradingOrderRepository orderRepository;
    @MockitoSpyBean private MarketHoursPolicy marketHoursPolicy;

    private UUID instrumentId;

    @BeforeEach
    void prepareFreshTcsQuote() {
        instrumentId = jdbcTemplate.queryForObject(
                "SELECT id FROM instrument WHERE exchange = 'NSE' AND symbol = 'TCS'", UUID.class);
        putQuote(Instant.now().minusSeconds(1), "OPEN");
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
    void validBuyLimitPreviewUsesPlacementRulesAndDoesNotMutateFinancialRows() throws Exception {
        TestAccount user = createAccount();
        OrderPlacementRequest input = request("BUY", "LIMIT", "DELIVERY", 2, "100.00");
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(user.email(), PASSWORD))
                        .contentType("application/json").content(objectMapper.writeValueAsBytes(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.currentEligiblePrice").value(125))
                .andExpect(jsonPath("$.estimatedOrderValue").value(200))
                .andExpect(jsonPath("$.estimatedBuyReservation").value(200))
                .andExpect(jsonPath("$.quoteFreshnessStatus").value("LIVE"));
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_record WHERE account_id = ?", user.accountId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM execution e JOIN trading_order o ON o.id = e.order_id WHERE o.account_id = ?", user.accountId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM position WHERE account_id = ?", user.accountId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger_entry WHERE account_id = ?", user.accountId())).isEqualTo(1);
    }

    @Test
    void sellAndStopMarketPreviewsExposeSellableQuantityAndStopReservation() throws Exception {
        TestAccount seller = createAccount();
        seedPosition(seller.accountId(), "DELIVERY", 7, 2);
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(seller.email(), PASSWORD))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(request("SELL", "LIMIT", "DELIVERY", 5, "100"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.sellableQuantity").value(5));

        TestAccount buyer = createAccount();
        OrderPlacementRequest stop = new OrderPlacementRequest("NSE", "TCS", "BUY", "STOP_MARKET",
                "DELIVERY", 2, null, new BigDecimal("130"));
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(buyer.email(), PASSWORD))
                        .contentType("application/json").content(objectMapper.writeValueAsBytes(stop)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.currentEligiblePrice").value(125))
                .andExpect(jsonPath("$.estimatedBuyReservation").value(260));
        assertBalance(buyer.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(buyer.accountId())).isZero();
    }

    @Test
    void previewReportsFundsRiskQuoteAndSessionFailuresWithoutMutations() throws Exception {
        TestAccount user = createAccount();
        OrderPlacementRequest tooLarge = request("BUY", "LIMIT", "DELIVERY", 2, "100");
        jdbcTemplate.update("INSERT INTO risk_limit (id, account_id, scope, limit_type, limit_value, enabled, created_at) "
                        + "VALUES (?, ?, 'ACCOUNT', 'MAX_ORDER_AMOUNT', 150, TRUE, ?)",
                UUID.randomUUID(), user.accountId(), Timestamp.from(Instant.now()));
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(user.email(), PASSWORD))
                        .contentType("application/json").content(objectMapper.writeValueAsBytes(tooLarge)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.applicableRiskLimitFailures[0]").value("Order value exceeds the active risk limit"));
        place(user, tooLarge, null).andExpect(status().isUnprocessableEntity());
        assertThat(orderCount(user.accountId())).isZero();
        assertBalance(user.accountId(), "100000.0000", "0.0000");

        jdbcTemplate.update("DELETE FROM risk_limit WHERE account_id = ?", user.accountId());
        OrderPlacementRequest expensive = request("BUY", "LIMIT", "DELIVERY", 1001, "100");
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(user.email(), PASSWORD))
                        .contentType("application/json").content(objectMapper.writeValueAsBytes(expensive)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.validationErrors[0]").value("Insufficient available virtual funds"));

        doReturn(false).when(marketHoursPolicy).isRegularSession(any(Instant.class));
        OrderPlacementRequest market = request("BUY", "MARKET", "DELIVERY", 1, null);
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(user.email(), PASSWORD))
                        .contentType("application/json").content(objectMapper.writeValueAsBytes(market)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.marketSessionEligibility").value("CLOSED"));
        doReturn(true).when(marketHoursPolicy).isRegularSession(any(Instant.class));

        putQuote(Instant.now().minusSeconds(601), "OPEN");
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(user.email(), PASSWORD))
                        .contentType("application/json").content(objectMapper.writeValueAsBytes(tooLarge)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quoteFreshnessStatus").value("STALE"))
                .andExpect(jsonPath("$.currentEligiblePrice").value(org.hamcrest.Matchers.nullValue()));
        jdbcTemplate.update("DELETE FROM market_quote WHERE instrument_id = ?", instrumentId);
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(user.email(), PASSWORD))
                        .contentType("application/json").content(objectMapper.writeValueAsBytes(tooLarge)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quoteFreshnessStatus").value("UNAVAILABLE"));
        assertThat(orderCount(user.accountId())).isZero();
        assertBalance(user.accountId(), "100000.0000", "0.0000");
    }

    @Test
    void previewRequiresAuthenticationAndReportsUnsupportedInstrument() throws Exception {
        mockMvc.perform(post("/api/v1/orders/preview").contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(request("BUY", "LIMIT", "DELIVERY", 1, "100"))))
                .andExpect(status().isUnauthorized());
        TestAccount user = createAccount();
        OrderPlacementRequest unsupported = new OrderPlacementRequest("NSE", "NOTSUPPORTED", "BUY", "LIMIT",
                "DELIVERY", 1, new BigDecimal("100"));
        mockMvc.perform(post("/api/v1/orders/preview").header("Authorization", basic(user.email(), PASSWORD))
                        .contentType("application/json").content(objectMapper.writeValueAsBytes(unsupported)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.validationErrors[0]").value("Unsupported or non-tradable instrument"));
        assertThat(orderCount(user.accountId())).isZero();
        assertBalance(user.accountId(), "100000.0000", "0.0000");
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
    void stopMarketRequestsRequirePositiveTriggerAndRejectUnrelatedPrices() throws Exception {
        TestAccount user = createAccount();
        place(user, new OrderPlacementRequest("NSE", "TCS", "BUY", "STOP_MARKET", "DELIVERY", 1, null), null)
                .andExpect(status().isBadRequest());
        place(user, new OrderPlacementRequest("NSE", "TCS", "BUY", "STOP_MARKET", "DELIVERY", 1,
                null, BigDecimal.ZERO), null).andExpect(status().isBadRequest());
        place(user, new OrderPlacementRequest("NSE", "TCS", "BUY", "STOP_MARKET", "DELIVERY", 1,
                new BigDecimal("100"), new BigDecimal("105")), null).andExpect(status().isBadRequest());
        place(user, new OrderPlacementRequest("NSE", "TCS", "BUY", "MARKET", "DELIVERY", 1,
                null, new BigDecimal("100")), null).andExpect(status().isBadRequest());
        place(user, new OrderPlacementRequest("NSE", "TCS", "BUY", "LIMIT", "DELIVERY", 1,
                new BigDecimal("100"), new BigDecimal("105")), null).andExpect(status().isBadRequest());
        assertThat(orderCount(user.accountId())).isZero();
        assertBalance(user.accountId(), "100000.0000", "0.0000");
    }

    @Test
    void accountRiskLimitStillRejectsStopMarketOrder() throws Exception {
        TestAccount user = createAccount();
        jdbcTemplate.update("INSERT INTO risk_limit (id, account_id, scope, limit_type, limit_value, enabled, created_at) "
                        + "VALUES (?, ?, 'ACCOUNT', 'MAX_ORDER_QUANTITY', 1, TRUE, ?)",
                UUID.randomUUID(), user.accountId(), Timestamp.from(Instant.now()));
        place(user, new OrderPlacementRequest("NSE", "TCS", "BUY", "STOP_MARKET", "DELIVERY", 2,
                null, new BigDecimal("105")), null).andExpect(status().isUnprocessableEntity());
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(orderCount(user.accountId())).isZero();
    }

    @Test
    void limitQuantityAndPriceChangesRecalculateBuyReservationInBothDirections() throws Exception {
        TestAccount user = createAccount();
        UUID id = orderId(place(user, request("BUY", "LIMIT", "DELIVERY", 2, "100"), null)
                .andExpect(status().isCreated()).andReturn());

        modify(user, id, new OrderModificationRequest(3L, new BigDecimal("110"), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestedQuantity").value(3))
                .andExpect(jsonPath("$.remainingQuantity").value(3)).andExpect(jsonPath("$.limitPrice").value(110));
        assertBalance(user.accountId(), "99670.0000", "330.0000");

        modify(user, id, new OrderModificationRequest(1L, new BigDecimal("90"), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestedQuantity").value(1));
        assertBalance(user.accountId(), "99910.0000", "90.0000");
        modify(user, id, new OrderModificationRequest(1L, new BigDecimal("90.00"), null))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM order_event WHERE order_id = ? AND event_type = 'ORDER_MODIFIED'", id))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM execution WHERE order_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM position WHERE account_id = ?", user.accountId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger_entry WHERE account_id = ?", user.accountId())).isEqualTo(1);
    }

    @Test
    void stopMarketQuantityAndTriggerCanBeModifiedAndReservationTracksTheNewTrigger() throws Exception {
        TestAccount user = createAccount();
        var stop = new OrderPlacementRequest("NSE", "TCS", "BUY", "STOP_MARKET", "DELIVERY", 1,
                null, new BigDecimal("130"));
        UUID id = orderId(place(user, stop, null).andExpect(status().isCreated()).andReturn());

        modify(user, id, new OrderModificationRequest(2L, null, new BigDecimal("150")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.triggerPrice").value(150));
        assertBalance(user.accountId(), "99700.0000", "300.0000");
        modify(user, id, new OrderModificationRequest(1L, null, new BigDecimal("100")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingQuantity").value(1));
        assertBalance(user.accountId(), "99875.0000", "125.0000");
    }

    @Test
    void sellQuantityIncreaseAndDecreaseAdjustOnlyReservedQuantity() throws Exception {
        TestAccount user = createAccount();
        seedPosition(user.accountId(), "DELIVERY", 10, 0);
        UUID id = orderId(place(user, request("SELL", "LIMIT", "DELIVERY", 2, "150"), null)
                .andExpect(status().isCreated()).andReturn());

        modify(user, id, new OrderModificationRequest(4L, null, null)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_quantity FROM position WHERE account_id = ?",
                Long.class, user.accountId())).isEqualTo(4L);
        modify(user, id, new OrderModificationRequest(1L, null, null)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM position WHERE account_id = ?",
                Long.class, user.accountId())).isEqualTo(10L);
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_quantity FROM position WHERE account_id = ?",
                Long.class, user.accountId())).isEqualTo(1L);
        assertBalance(user.accountId(), "100000.0000", "0.0000");
    }

    @Test
    void rejectsOrdersThatAreFilledCancelledMarketInvalidRiskLimitedOrOwnedByAnotherUser() throws Exception {
        TestAccount user = createAccount();
        UUID filled = orderId(place(user, request("BUY", "LIMIT", "DELIVERY", 1, "200"), null)
                .andExpect(status().isCreated()).andReturn());
        doReturn(true).when(marketHoursPolicy).isRegularSession(any(Instant.class));
        putQuote(Instant.now().minusSeconds(1), "OPEN");
        assertThat(orderExecutionService.executePending(filled)).isTrue();
        modify(user, filled, new OrderModificationRequest(2L, null, null)).andExpect(status().isConflict());

        TestAccount cancelledOwner = createAccount();
        UUID cancelled = orderId(place(cancelledOwner, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", cancelled)
                        .header("Authorization", basic(cancelledOwner.email(), PASSWORD)))
                .andExpect(status().isOk());
        modify(cancelledOwner, cancelled, new OrderModificationRequest(2L, null, null))
                .andExpect(status().isConflict());

        TestAccount marketOwner = createAccount();
        doReturn(true).when(marketHoursPolicy).isRegularSession(any(Instant.class));
        UUID market = orderId(place(marketOwner, request("BUY", "MARKET", "DELIVERY", 1, null), null)
                .andExpect(status().isCreated()).andReturn());
        modify(marketOwner, market, new OrderModificationRequest(2L, null, null))
                .andExpect(status().isConflict());

        TestAccount stranger = createAccount();
        UUID strangerOrder = orderId(place(stranger, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isCreated()).andReturn());
        modify(marketOwner, strangerOrder, new OrderModificationRequest(2L, null, null))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/orders/{id}", strangerOrder).contentType("application/json")
                        .content("{\"quantity\":2}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidValuesAndRiskLimitAreRejectedWithoutChangingReservations() throws Exception {
        TestAccount user = createAccount();
        UUID id = orderId(place(user, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isCreated()).andReturn());
        modify(user, id, new OrderModificationRequest(0L, null, null)).andExpect(status().isBadRequest());
        modify(user, id, new OrderModificationRequest(null, new BigDecimal("-1"), null))
                .andExpect(status().isBadRequest());
        modify(user, id, new OrderModificationRequest(null, null, new BigDecimal("105")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/orders/{id}", id)
                        .header("Authorization", basic(user.email(), PASSWORD)).contentType("application/json")
                        .content("{\"quantity\":2,\"side\":\"SELL\"}"))
                .andExpect(status().isBadRequest());

        jdbcTemplate.update("INSERT INTO risk_limit (id, account_id, scope, limit_type, limit_value, enabled, created_at) "
                        + "VALUES (?, ?, 'ACCOUNT', 'MAX_ORDER_QUANTITY', 1, TRUE, ?)",
                UUID.randomUUID(), user.accountId(), Timestamp.from(Instant.now()));
        modify(user, id, new OrderModificationRequest(2L, null, null)).andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT requested_quantity FROM trading_order WHERE id = ?",
                Long.class, id)).isEqualTo(1L);
        assertBalance(user.accountId(), "99900.0000", "100.0000");
    }

    @Test
    void increasesBeyondAvailableFundsOrFreeSellQuantityAreRejectedWithoutOverspendOrOversell() throws Exception {
        TestAccount buyer = createAccount();
        UUID buyId = orderId(place(buyer, request("BUY", "LIMIT", "DELIVERY", 1, "100000"), null)
                .andExpect(status().isCreated()).andReturn());
        modify(buyer, buyId, new OrderModificationRequest(null, new BigDecimal("100001"), null))
                .andExpect(status().isUnprocessableEntity());
        assertBalance(buyer.accountId(), "0.0000", "100000.0000");
        assertThat(jdbcTemplate.queryForObject("SELECT limit_price FROM trading_order WHERE id = ?",
                BigDecimal.class, buyId)).isEqualByComparingTo("100000");

        TestAccount seller = createAccount();
        seedPosition(seller.accountId(), "DELIVERY", 2, 0);
        UUID sellId = orderId(place(seller, request("SELL", "LIMIT", "DELIVERY", 1, "150"), null)
                .andExpect(status().isCreated()).andReturn());
        modify(seller, sellId, new OrderModificationRequest(3L, null, null))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM position WHERE account_id = ?",
                Long.class, seller.accountId())).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_quantity FROM position WHERE account_id = ?",
                Long.class, seller.accountId())).isEqualTo(1L);
    }

    @Test
    void modificationRechecksThatThePersistedQuoteIsFreshAndAvailable() throws Exception {
        TestAccount user = createAccount();
        UUID id = orderId(place(user, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isCreated()).andReturn());
        putQuote(Instant.now().minusSeconds(601), "OPEN");
        modify(user, id, new OrderModificationRequest(2L, null, null))
                .andExpect(status().isUnprocessableEntity());
        jdbcTemplate.update("DELETE FROM market_quote WHERE instrument_id = ?", instrumentId);
        modify(user, id, new OrderModificationRequest(2L, null, null))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject("SELECT requested_quantity FROM trading_order WHERE id = ?",
                Long.class, id)).isEqualTo(1L);
        assertBalance(user.accountId(), "99900.0000", "100.0000");
    }

    @Test
    void modificationFailureRollsBackOrderAndReservationAndSuccessCreatesOnlyAnEvent() throws Exception {
        TestAccount user = createAccount();
        UUID id = orderId(place(user, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isCreated()).andReturn());
        int initialEvents = count("SELECT COUNT(*) FROM order_event WHERE order_id = ?", id);
        jdbcTemplate.execute("ALTER TABLE order_event ADD CONSTRAINT ck_test_no_order_modified "
                + "CHECK (event_type <> 'ORDER_MODIFIED' OR order_id <> '" + id + "')");
        try {
            assertThatThrownBy(() -> modify(user, id, new OrderModificationRequest(2L, null, null)).andReturn())
                    .hasStackTraceContaining("CK_TEST_NO_ORDER_MODIFIED");
        } finally {
            jdbcTemplate.execute("ALTER TABLE order_event DROP CONSTRAINT ck_test_no_order_modified");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT requested_quantity FROM trading_order WHERE id = ?",
                Long.class, id)).isEqualTo(1L);
        assertBalance(user.accountId(), "99900.0000", "100.0000");
        assertThat(count("SELECT COUNT(*) FROM order_event WHERE order_id = ?", id)).isEqualTo(initialEvents);
        assertThat(count("SELECT COUNT(*) FROM execution WHERE order_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM position WHERE account_id = ?", user.accountId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger_entry WHERE account_id = ?", user.accountId())).isEqualTo(1);

        modify(user, id, new OrderModificationRequest(2L, null, null)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT event_type FROM order_event WHERE order_id = ? "
                + "ORDER BY occurred_at DESC FETCH FIRST 1 ROW ONLY", String.class, id)).isEqualTo("ORDER_MODIFIED");
    }

    @Test
    void concurrentModificationSerializesAgainstExecution() throws Exception {
        TestAccount user = createAccount();
        UUID id = orderId(place(user, request("BUY", "LIMIT", "DELIVERY", 1, "200"), null)
                .andExpect(status().isCreated()).andReturn());
        doReturn(true).when(marketHoursPolicy).isRegularSession(any(Instant.class));
        putQuote(Instant.now().minusSeconds(1), "OPEN");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> modified = executor.submit(() -> concurrentModify(user, id, ready, start));
            Future<Boolean> executed = executor.submit(() -> {
                ready.countDown(); start.await(); return orderExecutionService.executePending(id);
            });
            ready.await(); start.countDown();
            assertThat(modified.get()).isIn(200, 409);
            assertThat(executed.get()).isTrue();
        } finally { executor.shutdownNow(); }
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM trading_order WHERE id = ?", String.class, id))
                .isEqualTo("FILLED");
        assertThat(jdbcTemplate.queryForObject("SELECT executed_quantity FROM trading_order WHERE id = ?", Long.class, id))
                .isIn(1L, 2L);
        assertThat(count("SELECT COUNT(*) FROM execution WHERE order_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ledger_entry WHERE account_id = ?", user.accountId())).isEqualTo(2);
    }

    @Test
    void concurrentModificationSerializesAgainstCancellationAndReleasesReservationOnce() throws Exception {
        TestAccount user = createAccount();
        UUID id = orderId(place(user, request("BUY", "LIMIT", "DELIVERY", 1, "100"), null)
                .andExpect(status().isCreated()).andReturn());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> modified = executor.submit(() -> concurrentModify(user, id, ready, start));
            Future<Integer> cancelled = executor.submit(() -> {
                ready.countDown(); start.await();
                return mockMvc.perform(post("/api/v1/orders/{id}/cancel", id)
                                .header("Authorization", basic(user.email(), PASSWORD)))
                        .andReturn().getResponse().getStatus();
            });
            ready.await(); start.countDown();
            assertThat(modified.get()).isIn(200, 409);
            assertThat(cancelled.get()).isEqualTo(200);
        } finally { executor.shutdownNow(); }
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM trading_order WHERE id = ?", String.class, id))
                .isEqualTo("CANCELLED");
        assertBalance(user.accountId(), "100000.0000", "0.0000");
        assertThat(count("SELECT COUNT(*) FROM order_event WHERE order_id = ? AND event_type = 'ORDER_CANCELLED'", id))
                .isEqualTo(1);
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

    private int concurrentModify(TestAccount user, UUID orderId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown(); start.await();
        return modify(user, orderId, new OrderModificationRequest(2L, null, null))
                .andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions modify(TestAccount user, UUID orderId,
            OrderModificationRequest request) throws Exception {
        return mockMvc.perform(put("/api/v1/orders/{id}", orderId)
                .header("Authorization", basic(user.email(), PASSWORD))
                .contentType("application/json").content(objectMapper.writeValueAsBytes(request)));
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
