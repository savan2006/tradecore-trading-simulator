package com.tradecore.execution;

import com.tradecore.account.TradingAccountRepository;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.market.MarketDataFreshness;
import com.tradecore.market.MarketDataIngestionService;
import com.tradecore.market.MarketDataProvider;
import com.tradecore.market.MarketQuoteSnapshot;
import com.tradecore.order.OrderPlacementRequest;
import com.tradecore.order.OrderPlacementService;
import com.tradecore.order.OrderCancellationService;
import com.tradecore.order.TradingOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-execution-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=execution-test",
        "tradecore.security.password=execution-test-password", "tradecore.execution.scheduling.enabled=false"
})
class OrderExecutionServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderPlacementService placement;
    @Autowired private OrderExecutionService execution;
    @Autowired private OrderCancellationService cancellation;
    @Autowired private MarketDataIngestionService ingestion;
    @Autowired private TradingOrderRepository orders;
    @MockitoBean private MarketDataProvider provider;
    @MockitoSpyBean private MarketHoursPolicy marketHours;
    @MockitoSpyBean private com.tradecore.ledger.LedgerEntryRepository ledger;
    private UUID instrumentId;

    @BeforeEach
    void setup() {
        instrumentId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        doReturn(true).when(marketHours).isRegularSession(any(Instant.class));
        putQuote("100", "101", "99", Instant.now(), "OPEN");
    }

    @Test
    void marketBuyExecutesAndSettlesBalancePositionLedgerEventAndOrder() {
        Account user = account();
        UUID order = place(user, "BUY", "MARKET", 2, null);

        assertThat(execution.executePending(order)).isTrue();

        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, order)).isEqualTo("FILLED");
        assertThat(jdbc.queryForObject("select executed_quantity from trading_order where id=?", Long.class, order)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select price from execution where order_id=?", BigDecimal.class, order)).isEqualByComparingTo("101");
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, order)).isEqualTo(1);
        assertBalance(user.account, "99798.0000", "0.0000");
        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, user.account)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select average_price from position where account_id=?", BigDecimal.class, user.account)).isEqualByComparingTo("101");
        assertThat(jdbc.queryForObject("select entry_type from ledger_entry where account_id=? and entry_type<>'INITIAL_DEPOSIT'", String.class, user.account)).isEqualTo("TRADE_DEBIT");
        assertThat(jdbc.queryForObject("select amount from ledger_entry where account_id=? and entry_type='TRADE_DEBIT'", BigDecimal.class, user.account)).isEqualByComparingTo("-202.0000");
        assertEvent(order);
    }

    @Test
    void configuredHolidayBlocksExecutionWithoutFinancialMutation() {
        Account user = account();
        UUID order = place(user, "BUY", "MARKET", 2, null);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        jdbc.update("delete from market_session where trading_date=?", today);
        jdbc.update("insert into market_session (id,trading_date,session_state,holiday,description,active) "
                        + "values (?,?,'HOLIDAY',true,'test holiday',true)", UUID.randomUUID(), today);
        doCallRealMethod().when(marketHours).isRegularSession(any(Instant.class));

        assertThat(execution.executePending(order)).isFalse();
        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, order)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, order)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from position where account_id=?", Integer.class, user.account)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where account_id=? and entry_type<>'INITIAL_DEPOSIT'",
                Integer.class, user.account)).isZero();
        assertBalance(user.account, "99800.0000", "200.0000");
    }

    @Test
    void marketSellExecutesCreditsAndRealizesPositionPnl() {
        Account user = account();
        seedPosition(user.account, 5, 0, "100");
        UUID order = place(user, "SELL", "MARKET", 2, null);

        assertThat(execution.executePending(order)).isTrue();

        assertBalance(user.account, "100198.0000", "0.0000");
        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, user.account)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select reserved_quantity from position where account_id=?", Long.class, user.account)).isZero();
        assertThat(jdbc.queryForObject("select realized_pnl from position where account_id=?", BigDecimal.class, user.account)).isEqualByComparingTo("-2.0000");
        assertThat(jdbc.queryForObject("select amount from ledger_entry where account_id=? and entry_type='TRADE_CREDIT'", BigDecimal.class, user.account)).isEqualByComparingTo("198.0000");
    }

    @Test
    void limitBuyExecutesWhenAskSatisfiesLimitAndRemainsPendingOtherwise() {
        Account yes = account();
        UUID yesOrder = place(yes, "BUY", "LIMIT", 2, "102");
        assertThat(execution.executePending(yesOrder)).isTrue();
        Account no = account();
        UUID noOrder = place(no, "BUY", "LIMIT", 2, "100");
        assertThat(execution.executePending(noOrder)).isFalse();
        assertPending(noOrder);
    }

    @Test
    void limitSellExecutesWhenBidSatisfiesLimitAndRemainsPendingOtherwise() {
        Account yes = account(); seedPosition(yes.account, 3, 0, "95");
        UUID yesOrder = place(yes, "SELL", "LIMIT", 1, "98");
        assertThat(execution.executePending(yesOrder)).isTrue();
        Account no = account(); seedPosition(no.account, 3, 0, "95");
        UUID noOrder = place(no, "SELL", "LIMIT", 1, "100");
        assertThat(execution.executePending(noOrder)).isFalse();
        assertPending(noOrder);
    }

    @Test
    void buyStopRemainsPendingBeforeTriggerAndExecutesAtTheCurrentAskAfterTrigger() {
        Account user = account();
        UUID order = placeStop(user, "BUY", 2, "105");

        assertThat(jdbc.queryForObject("select trigger_price from trading_order where id=?", BigDecimal.class, order))
                .isEqualByComparingTo("105");
        assertThat(jdbc.queryForObject("select reserved_amount from trading_order where id=?", BigDecimal.class, order))
                .isEqualByComparingTo("210.0000");
        assertThat(execution.executePending(order)).isFalse();
        assertPending(order);
        assertThat(jdbc.queryForObject("select count(*) from order_event where order_id=?", Integer.class, order)).isEqualTo(1);
        assertBalance(user.account, "99790.0000", "210.0000");

        putQuote("108", "110", "107", Instant.now().minusSeconds(1), "OPEN");
        assertThat(execution.executePending(order)).isTrue();
        assertThat(jdbc.queryForObject("select price from execution where order_id=?", BigDecimal.class, order))
                .isEqualByComparingTo("110");
        assertBalance(user.account, "99780.0000", "0.0000");
        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, order)).isEqualTo("FILLED");
        assertEvent(order);
    }

    @Test
    void sellStopRemainsPendingAboveTriggerThenExecutesAtTheCurrentBid() {
        Account user = account();
        seedPosition(user.account, 3, 0, "100");
        UUID order = placeStop(user, "SELL", 1, "97");

        assertThat(execution.executePending(order)).isFalse();
        assertPending(order);
        assertThat(jdbc.queryForObject("select reserved_quantity from position where account_id=?", Long.class, user.account))
                .isEqualTo(1L);

        putQuote("96", "97", "95", Instant.now().minusSeconds(1), "OPEN");
        assertThat(execution.executePending(order)).isTrue();
        assertThat(jdbc.queryForObject("select price from execution where order_id=?", BigDecimal.class, order))
                .isEqualByComparingTo("95");
        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, user.account))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("select reserved_quantity from position where account_id=?", Long.class, user.account))
                .isZero();
    }

    @Test
    void stopRequiresFreshEligibleQuoteAndKeepsFundsReservedWhenGapExceedsAvailableTopUp() {
        Account stale = account();
        UUID staleOrder = placeStop(stale, "BUY", 1, "105");
        putQuote("110", "111", "109", Instant.now().minusSeconds(601), "OPEN");
        assertThat(execution.executePending(staleOrder)).isFalse();
        assertPending(staleOrder);
        putQuote("110", "111", "109", Instant.now().minusSeconds(1), "OPEN");
        Account missing = account();
        UUID missingOrder = placeStop(missing, "BUY", 1, "105");
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        assertThat(execution.executePending(missingOrder)).isFalse();
        assertPending(missingOrder);

        putQuote("100", "101", "99", Instant.now().minusSeconds(1), "OPEN");
        Account gap = account();
        UUID gapOrder = placeStop(gap, "BUY", 1, "50000");
        putQuote("160000", "160001", "159999", Instant.now().minusSeconds(1), "OPEN");
        assertThat(execution.executePending(gapOrder)).isFalse();
        assertPending(gapOrder);
        assertBalance(gap.account, "50000.0000", "50000.0000");
        assertThat(jdbc.queryForObject("select count(*) from position where account_id=?", Integer.class, gap.account)).isZero();
    }

    @Test
    void stopCancellationReleasesReservationOnceAndRacesWithExecutionSafely() throws Exception {
        Account cancelledUser = account();
        UUID cancelledOrder = placeStop(cancelledUser, "BUY", 2, "105");
        var cancelled = cancellation.cancel(email(cancelledUser.account), cancelledOrder);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.releasedFunds()).isEqualByComparingTo("210.0000");
        assertBalance(cancelledUser.account, "100000.0000", "0.0000");
        assertThat(cancellation.cancel(email(cancelledUser.account), cancelledOrder).releasedFunds())
                .isEqualByComparingTo("0.0000");

        Account raceUser = account();
        UUID raceOrder = placeStop(raceUser, "BUY", 1, "100");
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var execute = pool.submit(() -> { start.await(); return execution.executePending(raceOrder); });
            var cancel = pool.submit(() -> {
                start.await();
                try {
                    cancellation.cancel(email(raceUser.account), raceOrder);
                    return true;
                } catch (ResponseStatusException alreadyExecuted) {
                    return false;
                }
            });
            start.countDown();
            boolean executed = execute.get(10, TimeUnit.SECONDS);
            boolean cancelledInRace = cancel.get(10, TimeUnit.SECONDS);
            assertThat((executed ? 1 : 0) + (cancelledInRace ? 1 : 0)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, raceOrder))
                .isEqualTo(jdbc.queryForObject("select status from trading_order where id=?", String.class, raceOrder).equals("FILLED") ? 1 : 0);
    }

    @Test
    void staleAndMissingQuotesPreventExecution() {
        Account stale = account(); UUID staleOrder = place(stale, "BUY", "LIMIT", 1, "110");
        putQuote("100", "101", "99", Instant.now().minusSeconds(601), "OPEN");
        assertThat(execution.executePending(staleOrder)).isFalse();
        assertPending(staleOrder);
        putQuote("100", "101", "99", Instant.now(), "OPEN");
        Account missing = account(); UUID missingOrder = place(missing, "BUY", "LIMIT", 1, "110");
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        assertThat(execution.executePending(missingOrder)).isFalse();
        assertPending(missingOrder);
    }

    @Test
    void cancelledAndAlreadyFilledOrdersAreNeverExecutedTwice() {
        Account user = account(); UUID cancelled = place(user, "BUY", "LIMIT", 1, "110");
        jdbc.update("update trading_order set status='CANCELLED' where id=?", cancelled);
        assertThat(execution.executePending(cancelled)).isFalse();
        UUID filled = place(user, "BUY", "LIMIT", 1, "110");
        assertThat(execution.executePending(filled)).isTrue();
        assertThat(execution.executePending(filled)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, filled)).isEqualTo(1);
    }

    @Test
    void inactiveAccountDoesNotExecuteOrSettle() {
        Account user = account(); UUID order = place(user, "BUY", "LIMIT", 1, "110");
        jdbc.update("update trading_account set status='RESTRICTED' where id=?", user.account);
        assertThat(execution.executePending(order)).isFalse();
        assertPending(order);
        assertBalance(user.account, "99890.0000", "110.0000");
    }

    @Test
    void instrumentThatBecomesNonTradableDoesNotExecute() {
        Account user = account(); UUID order = place(user, "BUY", "LIMIT", 1, "110");
        jdbc.update("update instrument set tradable=false where id=?", instrumentId);
        assertThat(execution.executePending(order)).isFalse();
        assertPending(order);
        jdbc.update("update instrument set tradable=true where id=?", instrumentId);
    }

    @Test
    void sellFillReleasesTheEntireReservedQuantity() {
        Account user = account(); seedPosition(user.account, 6, 0, "80");
        UUID order = place(user, "SELL", "LIMIT", 4, "90");
        assertThat(jdbc.queryForObject("select reserved_quantity from position where account_id=?", Long.class, user.account)).isEqualTo(4L);
        assertThat(execution.executePending(order)).isTrue();
        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, user.account)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select reserved_quantity from position where account_id=?", Long.class, user.account)).isZero();
    }

    @Test
    void insufficientSellReservationLeavesOrderAndPositionUnchanged() {
        Account user = account(); seedPosition(user.account, 4, 0, "100");
        UUID order = place(user, "SELL", "LIMIT", 2, "90");
        jdbc.update("update position set reserved_quantity=0 where account_id=?", user.account);
        assertThat(execution.executePending(order)).isFalse();
        assertPending(order);
        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, user.account)).isEqualTo(4L);
    }

    @Test
    void closedMarketSessionBlocksExecutionRegardlessOfQuoteStatus() {
        Account user = account(); UUID order = place(user, "BUY", "MARKET", 1, null);
        putQuote("100", "101", "99", Instant.now(), "OPEN");
        doReturn(false).when(marketHours).isRegularSession(any(Instant.class));
        assertThat(execution.executePending(order)).isFalse();
        assertPending(order);
    }

    @Test
    void unknownStatusFromRealIngestionCanPlaceAndExecuteOnlyDuringRegularSession() {
        Instant updated = Instant.now();
        when(provider.getQuotes(any())).thenReturn(List.of(new MarketQuoteSnapshot(
                "NSE", "TCS", null, updated.minusSeconds(1), new BigDecimal("100"),
                new BigDecimal("102"), new BigDecimal("98"), null, new BigDecimal("99"),
                1000L, new BigDecimal("100"), "NSE_MCP_CM_MARKET", updated)));
        when(provider.getDataFreshness()).thenReturn(Optional.of(new MarketDataFreshness(
                "NSE_MCP_CM_MARKET", true, updated, java.time.Duration.ofMinutes(1))));

        ingestion.ingestCurrentQuotes(List.of("TCS"));
        assertThat(jdbc.queryForObject("select market_status from market_quote where instrument_id=?", String.class, instrumentId))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select data_status from market_quote where instrument_id=?", String.class, instrumentId))
                .isEqualTo("LIVE");

        doReturn(true).when(marketHours).isRegularSession(any(Instant.class));
        Account inside = account();
        UUID order = place(inside, "BUY", "MARKET", 1, null);
        assertThat(execution.executePending(order)).isTrue();
        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, order)).isEqualTo("FILLED");

        doReturn(false).when(marketHours).isRegularSession(any(Instant.class));
        Account outside = account();
        assertThatThrownBy(() -> place(outside, "BUY", "MARKET", 1, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("open regular market session");
        assertThat(jdbc.queryForObject("select count(*) from trading_order where account_id=?", Integer.class, outside.account))
                .isZero();
    }

    @Test
    void buyPositionAverageCostUsesExistingAndNewQuantities() {
        Account user = account(); seedPosition(user.account, 2, 0, "90");
        UUID order = place(user, "BUY", "LIMIT", 2, "110");
        assertThat(execution.executePending(order)).isTrue();
        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, user.account)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("select average_price from position where account_id=?", BigDecimal.class, user.account)).isEqualByComparingTo("95.500000");
    }

    @Test
    void fillEventAndExecutionRetainQuoteReferenceAndCompleteQuantities() {
        Account user = account(); UUID order = place(user, "BUY", "LIMIT", 3, "110");
        assertThat(execution.executePending(order)).isTrue();
        assertEvent(order);
        assertThat(jdbc.queryForObject("select remaining_quantity from trading_order where id=?", Long.class, order)).isZero();
        assertThat(jdbc.queryForObject("select quantity from execution where order_id=?", Long.class, order)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select market_price from execution where order_id=?", BigDecimal.class, order)).isEqualByComparingTo("100");
    }

    @Test
    void buyReservationIsConsumedAndUnusedLimitReservationReleased() {
        Account user = account(); UUID order = place(user, "BUY", "LIMIT", 2, "120");
        assertBalance(user.account, "99760.0000", "240.0000");
        assertThat(execution.executePending(order)).isTrue();
        assertBalance(user.account, "99798.0000", "0.0000");
    }

    @Test
    void marketBuyPriceJumpWithoutAvailableTopUpLeavesOrderPending() {
        Account user = account(); UUID order = place(user, "BUY", "MARKET", 2, null);
        putQuote("100", "60000", "99", Instant.now(), "OPEN");
        assertThat(execution.executePending(order)).isFalse();
        assertPending(order);
        assertBalance(user.account, "99800.0000", "200.0000");
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, order)).isZero();
    }

    @Test
    void rollbackOnLedgerFailureRevertsExecutionBalancesPositionAndOrder() {
        Account user = account(); UUID order = place(user, "BUY", "LIMIT", 2, "110");
        doThrow(new IllegalStateException("controlled ledger failure")).when(ledger).saveAndFlush(any());
        assertThatThrownBy(() -> execution.executePending(order)).hasMessageContaining("controlled ledger failure");
        assertPending(order);
        assertBalance(user.account, "99780.0000", "220.0000");
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, order)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from position where account_id=?", Integer.class, user.account)).isZero();
    }

    @Test
    void concurrentExecutionAttemptsSettleOnlyOnce() throws Exception {
        Account user = account(); UUID order = place(user, "BUY", "LIMIT", 2, "110");
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return execution.executePending(order); });
            var b = pool.submit(() -> { start.await(); return execution.executePending(order); });
            start.countDown();
            assertThat((a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, order)).isEqualTo(1);
        assertBalance(user.account, "99798.0000", "0.0000");
        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, user.account)).isEqualTo(2L);
    }

    private Account account() {
        RegistrationResponse result = registration.register(new RegistrationRequest(
                "exec-" + UUID.randomUUID() + "@example.invalid", "Execution-Test-Password-93!", "Execution Test"));
        return new Account(result.accountId());
    }
    private UUID place(Account user, String side, String type, long quantity, String limit) {
        var response = placement.placeOrder(jdbc.queryForObject("select email from app_user where id=(select user_id from trading_account where id=?)", String.class, user.account),
                new OrderPlacementRequest("NSE", "TCS", side, type, "DELIVERY", quantity,
                        limit == null ? null : new BigDecimal(limit)), null);
        return response.orderId();
    }
    private UUID placeStop(Account user, String side, long quantity, String trigger) {
        var response = placement.placeOrder(email(user.account),
                new OrderPlacementRequest("NSE", "TCS", side, "STOP_MARKET", "DELIVERY", quantity,
                        null, new BigDecimal(trigger)), null);
        return response.orderId();
    }
    private String email(UUID account) {
        return jdbc.queryForObject("select email from app_user where id=(select user_id from trading_account where id=?)",
                String.class, account);
    }
    private void putQuote(String last, String ask, String bid, Instant updated, String status) {
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        Instant now = Instant.now();
        jdbc.update("insert into market_quote (id,instrument_id,last_price,bid_price,ask_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,?,?,?,'LIVE')",
                UUID.randomUUID(), instrumentId, new BigDecimal(last), new BigDecimal(bid), new BigDecimal(ask), Timestamp.from(updated), Timestamp.from(updated), Timestamp.from(now), status);
    }
    private void seedPosition(UUID account, long quantity, long reserved, String average) {
        jdbc.update("insert into position (id,account_id,instrument_id,trading_mode,quantity,reserved_quantity,average_price,realized_pnl,updated_at,version) values (?,?,?,'DELIVERY',?,?,?,?,?,0)",
                UUID.randomUUID(), account, instrumentId, quantity, reserved, new BigDecimal(average), BigDecimal.ZERO, Timestamp.from(Instant.now()));
    }
    private void assertBalance(UUID account, String available, String reserved) {
        assertThat(jdbc.queryForObject("select available_balance from trading_account where id=?", BigDecimal.class, account)).isEqualByComparingTo(available);
        assertThat(jdbc.queryForObject("select reserved_balance from trading_account where id=?", BigDecimal.class, account)).isEqualByComparingTo(reserved);
    }
    private void assertPending(UUID order) {
        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, order)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, order)).isZero();
    }
    private void assertEvent(UUID order) {
        assertThat(jdbc.queryForObject("select previous_state from order_event where order_id=? order by occurred_at desc fetch first 1 row only", String.class, order)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select new_state from order_event where order_id=? order by occurred_at desc fetch first 1 row only", String.class, order)).isEqualTo("FILLED");
        assertThat(jdbc.queryForObject("select event_type from order_event where order_id=? order by occurred_at desc fetch first 1 row only", String.class, order)).isEqualTo("ORDER_FILLED");
    }
    private record Account(UUID account) {}
}
