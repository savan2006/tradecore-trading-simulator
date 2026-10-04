package com.tradecore.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradecore.execution.OrderExecutionService;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-cancellation-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=cancellation-test",
        "tradecore.security.password=cancellation-test-password", "tradecore.execution.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class OrderCancellationApiTest {
    private static final String PASSWORD = "Cancellation-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderPlacementService placement;
    @Autowired private OrderCancellationService cancellation;
    @Autowired private OrderExecutionService execution;
    @MockitoSpyBean private MarketHoursPolicy marketHours;
    @MockitoSpyBean private OrderEventRepository events;
    private UUID instrumentId;

    @BeforeEach
    void prepareQuote() {
        instrumentId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        doReturn(true).when(marketHours).isRegularSession(any(Instant.class));
        putQuote(Instant.now());
    }

    @Test
    void authenticatedOwnerCanCancelBuyAndReleaseReservedFundsWithoutSettlement() throws Exception {
        Account owner = account(); UUID order = place(owner, "BUY", 2, "150");
        assertBalance(owner.id, "99700.0000", "300.0000");

        cancel(owner, order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.releasedFunds").value(300.0));

        assertBalance(owner.id, "100000.0000", "0.0000");
        assertThat(count("select count(*) from execution where order_id=?", order)).isZero();
        assertThat(count("select count(*) from ledger_entry where account_id=? and entry_type in ('TRADE_DEBIT','TRADE_CREDIT')", owner.id)).isZero();
        assertEvent(order);
    }

    @Test
    void authenticatedOwnerCanCancelSellAndReleaseReservedQuantityOnly() throws Exception {
        Account owner = account(); seedPosition(owner.id, 7, 0);
        UUID order = place(owner, "SELL", 3, "90");

        cancel(owner, order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.releasedSellQuantity").value(3));

        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, owner.id)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("select reserved_quantity from position where account_id=?", Long.class, owner.id)).isZero();
        assertThat(jdbc.queryForObject("select realized_pnl from position where account_id=?", BigDecimal.class, owner.id)).isEqualByComparingTo("0.0000");
        assertBalance(owner.id, "100000.0000", "0.0000");
        assertThat(count("select count(*) from ledger_entry where account_id=? and entry_type in ('TRADE_DEBIT','TRADE_CREDIT')", owner.id)).isZero();
    }

    @Test
    void anotherUserCannotCancelTheOrder() throws Exception {
        Account owner = account(), other = account(); UUID order = place(owner, "BUY", 1, "120");

        cancel(other, order).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, order)).isEqualTo("PENDING");
        assertBalance(owner.id, "99880.0000", "120.0000");
        assertThat(count("select count(*) from order_event where order_id=?", order)).isEqualTo(1);
    }

    @Test
    void filledOrderCannotBeCancelled() throws Exception {
        Account owner = account(); UUID order = place(owner, "BUY", 1, "120");
        assertThat(execution.executePending(order)).isTrue();

        cancel(owner, order).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, order)).isEqualTo("FILLED");
        assertThat(count("select count(*) from execution where order_id=?", order)).isEqualTo(1);
        assertThat(count("select count(*) from order_event where order_id=? and event_type='ORDER_CANCELLED'", order)).isZero();
    }

    @Test
    void repeatedCancellationIsIdempotentAndDoesNotReleaseOrEmitTwice() throws Exception {
        Account owner = account(); UUID order = place(owner, "BUY", 2, "150");

        cancel(owner, order).andExpect(status().isOk());
        cancel(owner, order).andExpect(status().isOk()).andExpect(jsonPath("$.releasedFunds").value(0.0));

        assertBalance(owner.id, "100000.0000", "0.0000");
        assertThat(count("select count(*) from order_event where order_id=? and event_type='ORDER_CANCELLED'", order)).isEqualTo(1);
        assertThat(count("select count(*) from execution where order_id=?", order)).isZero();
    }

    @Test
    void cancellationRollsBackWhenEventCreationFails() {
        Account owner = account(); UUID order = place(owner, "BUY", 2, "150");
        doThrow(new IllegalStateException("controlled event failure")).when(events).saveAndFlush(any(OrderEvent.class));

        assertThatThrownBy(() -> cancellation.cancel(owner.email, order))
                .hasMessageContaining("controlled event failure");

        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, order)).isEqualTo("PENDING");
        assertBalance(owner.id, "99700.0000", "300.0000");
        assertThat(count("select count(*) from order_event where order_id=? and event_type='ORDER_CANCELLED'", order)).isZero();
    }

    @Test
    void concurrentCancellationAndExecutionHaveExactlyOneWinner() throws Exception {
        Account owner = account(); UUID order = place(owner, "BUY", 2, "150");
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var cancelResult = pool.submit(() -> {
                start.await();
                try { cancellation.cancel(owner.email, order); return "CANCELLED"; }
                catch (ResponseStatusException conflict) { return "LOST"; }
            });
            var executionResult = pool.submit(() -> { start.await(); return execution.executePending(order); });
            start.countDown();
            String cancelState = cancelResult.get(10, TimeUnit.SECONDS);
            boolean executed = executionResult.get(10, TimeUnit.SECONDS);
            String finalState = jdbc.queryForObject("select status from trading_order where id=?", String.class, order);
            assertThat(("CANCELLED".equals(cancelState) ? 1 : 0) + (executed ? 1 : 0)).isEqualTo(1);
            if ("CANCELLED".equals(finalState)) {
                assertThat(executed).isFalse();
                assertBalance(owner.id, "100000.0000", "0.0000");
                assertThat(count("select count(*) from execution where order_id=?", order)).isZero();
            } else {
                assertThat(finalState).isEqualTo("FILLED");
                assertThat(cancelState).isEqualTo("LOST");
                assertThat(executed).isTrue();
                assertThat(count("select count(*) from execution where order_id=?", order)).isEqualTo(1);
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void concurrentCancellationRequestsReleaseAndCreateEventOnlyOnce() throws Exception {
        Account owner = account(); UUID order = place(owner, "BUY", 2, "150");
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> concurrentCancel(owner, order, ready, start));
            var second = pool.submit(() -> concurrentCancel(owner, order, ready, start));
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("CANCELLED");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("CANCELLED");
        } finally { pool.shutdownNow(); }
        assertBalance(owner.id, "100000.0000", "0.0000");
        assertThat(count("select count(*) from order_event where order_id=? and event_type='ORDER_CANCELLED'", order)).isEqualTo(1);
    }

    @Test
    void unauthenticatedCancellationIsRejected() throws Exception {
        Account owner = account(); UUID order = place(owner, "BUY", 1, "120");
        mvc.perform(post("/api/v1/orders/{id}/cancel", order)).andExpect(status().isUnauthorized());
    }

    private String concurrentCancel(Account owner, UUID order, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); start.await(); cancellation.cancel(owner.email, order); return "CANCELLED";
    }
    private org.springframework.test.web.servlet.ResultActions cancel(Account account, UUID order) throws Exception {
        return mvc.perform(post("/api/v1/orders/{id}/cancel", order)
                .header("Authorization", basic(account.email, PASSWORD)));
    }
    private UUID place(Account account, String side, long quantity, String price) {
        return placement.placeOrder(account.email,
                new OrderPlacementRequest("NSE", "TCS", side, "LIMIT", "DELIVERY", quantity, new BigDecimal(price)), null).orderId();
    }
    private Account account() {
        String email = "cancel-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse result = registration.register(new RegistrationRequest(email, PASSWORD, "Cancellation Test"));
        return new Account(email, result.accountId());
    }
    private void seedPosition(UUID accountId, long quantity, long reserved) {
        jdbc.update("insert into position (id,account_id,instrument_id,trading_mode,quantity,reserved_quantity,average_price,realized_pnl,updated_at,version) values (?,?,?,'DELIVERY',?,?,100,0,?,0)",
                UUID.randomUUID(), accountId, instrumentId, quantity, reserved, Timestamp.from(Instant.now()));
    }
    private void putQuote(Instant updated) {
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        jdbc.update("insert into market_quote (id,instrument_id,last_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,100,?,?,?,'OPEN','LIVE')",
                UUID.randomUUID(), instrumentId, Timestamp.from(updated), Timestamp.from(updated), Timestamp.from(Instant.now()));
    }
    private org.springframework.test.web.servlet.ResultActions cancel(Account account, String order) throws Exception {
        return cancel(account, UUID.fromString(order));
    }
    private void assertBalance(UUID account, String available, String reserved) {
        assertThat(jdbc.queryForObject("select available_balance from trading_account where id=?", BigDecimal.class, account)).isEqualByComparingTo(available);
        assertThat(jdbc.queryForObject("select reserved_balance from trading_account where id=?", BigDecimal.class, account)).isEqualByComparingTo(reserved);
    }
    private void assertEvent(UUID order) {
        assertThat(jdbc.queryForObject("select previous_state from order_event where order_id=? and event_type='ORDER_CANCELLED'", String.class, order)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select new_state from order_event where order_id=? and event_type='ORDER_CANCELLED'", String.class, order)).isEqualTo("CANCELLED");
    }
    private int count(String sql, Object arg) { return jdbc.queryForObject(sql, Integer.class, arg); }
    private static String basic(String email, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    private record Account(String email, UUID id) { }
}
