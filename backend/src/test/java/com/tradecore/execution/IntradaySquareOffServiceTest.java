package com.tradecore.execution;

import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.order.OrderPlacementRequest;
import com.tradecore.order.OrderPlacementService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doCallRealMethod;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-squareoff-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=squareoff-test",
        "tradecore.security.password=squareoff-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false",
        "tradecore.intraday.square-off-check-interval=PT1H"
})
class IntradaySquareOffServiceTest {
    private static final Instant AFTER_CLOSE = Instant.parse("2026-10-05T10:00:00Z"); // 15:30 Asia/Kolkata
    private static final Instant WINDOW_START = Instant.parse("2026-10-05T09:50:00Z"); // 15:20 Asia/Kolkata
    private static final Instant BEFORE_WINDOW = WINDOW_START.minusSeconds(1);
    private static final String PASSWORD = "Square-Off-Test-Password-93!";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderPlacementService placement;
    @Autowired private OrderExecutionService execution;
    @Autowired private IntradaySquareOffService squareOff;
    @MockitoSpyBean private MarketHoursPolicy marketHours;
    private UUID instrumentId;

    @BeforeEach
    void setup() {
        instrumentId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        doReturn(true).when(marketHours).isRegularSession(any(Instant.class));
        putQuote("100", "101", "99", "LIVE");
    }

    @Test
    void sessionEndCancelsOnlyPendingIntradayOrdersAndReleasesReservation() {
        Account account = account();
        UUID intraday = place(account, "INTRADAY", "BUY", 2);
        UUID intradaySellPosition = seedPosition(account.id, 3, 0, "100");
        UUID intradaySell = place(account, "INTRADAY", "SELL", 2);
        UUID delivery = place(account, "DELIVERY", "BUY", 2);
        putQuote("100", "101", "99", "STALE");
        assertThat(squareOff.runOnce(WINDOW_START)).isZero();

        assertThat(orderStatus(intraday)).isEqualTo("CANCELLED");
        assertThat(orderStatus(intradaySell)).isEqualTo("CANCELLED");
        assertThat(orderStatus(delivery)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select available_balance from trading_account where id=?", BigDecimal.class, account.id))
                .isEqualByComparingTo("99780");
        assertThat(jdbc.queryForObject("select reserved_balance from trading_account where id=?", BigDecimal.class, account.id))
                .isEqualByComparingTo("220");
        assertThat(jdbc.queryForObject("select count(*) from order_event where order_id=? and event_type='ORDER_CANCELLED'", Integer.class, intraday))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, intraday)).isZero();
        assertThat(jdbc.queryForObject("select reserved_quantity from position where id=?", Long.class, intradaySellPosition)).isZero();
        assertThat(jdbc.queryForObject("select quantity from position where id=?", Long.class, intradaySellPosition)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, intradaySell)).isZero();
    }

    @Test
    void openIntradayPositionUsesRegularSellSettlementAndRepeatIsIdempotent() {
        Account account = account();
        UUID positionId = seedPosition(account.id, 5, 0, "100");
        assertThat(squareOff.runOnce(AFTER_CLOSE)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from position where id=?", Long.class, positionId)).isZero();
        assertThat(jdbc.queryForObject("select reserved_quantity from position where id=?", Long.class, positionId)).isZero();
        assertThat(jdbc.queryForObject("select realized_pnl from position where id=?", BigDecimal.class, positionId))
                .isEqualByComparingTo("-5");
        assertThat(jdbc.queryForObject("select available_balance from trading_account where id=?", BigDecimal.class, account.id))
                .isEqualByComparingTo("100495");
        assertThat(jdbc.queryForObject("select count(*) from execution where account_id=? and trading_mode='INTRADAY'", Integer.class, account.id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(amount) from ledger_entry where account_id=? and entry_type='TRADE_CREDIT'", BigDecimal.class, account.id))
                .isEqualByComparingTo("495");
        assertThat(jdbc.queryForObject("select count(*) from trading_order where account_id=? and side='SELL' and trading_mode='INTRADAY' and status='FILLED'", Integer.class, account.id))
                .isEqualTo(1);

        assertThat(squareOff.runOnce(AFTER_CLOSE.plusSeconds(30))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from execution where account_id=?", Integer.class, account.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where account_id=? and entry_type='TRADE_CREDIT'", Integer.class, account.id))
                .isEqualTo(1);
    }

    @Test
    void staleOrMissingQuoteLeavesPositionAndFinancialStateUntouched() {
        Account stale = account(); UUID stalePosition = seedPosition(stale.id, 3, 0, "100");
        putQuote("100", "101", "99", "STALE");
        assertThat(squareOff.runOnce(WINDOW_START)).isZero();
        assertPositionOpen(stale.id, stalePosition, 3);
        assertThat(jdbc.queryForObject("select count(*) from execution where account_id=?", Integer.class, stale.id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where account_id=? and entry_type<>'INITIAL_DEPOSIT'", Integer.class, stale.id)).isZero();

        Account missing = account(); UUID missingPosition = seedPosition(missing.id, 4, 0, "100");
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        assertThat(squareOff.runOnce(AFTER_CLOSE.plusSeconds(30))).isZero();
        assertPositionOpen(missing.id, missingPosition, 4);
        assertThat(jdbc.queryForObject("select count(*) from execution where account_id=?", Integer.class, missing.id)).isZero();
    }

    @Test
    void executionAndSquareOffSerializeWithoutDuplicateSettlement() throws Exception {
        Account account = account();
        UUID positionId = seedPosition(account.id, 5, 0, "100");
        UUID pendingSell = place(account, "INTRADAY", "SELL", 2);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var fill = pool.submit(() -> { start.await(); return execution.executePending(pendingSell); });
            var close = pool.submit(() -> { start.await(); return squareOff.runOnce(AFTER_CLOSE); });
            start.countDown();
            fill.get(15, TimeUnit.SECONDS);
            close.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }

        assertThat(jdbc.queryForObject("select quantity from position where id=?", Long.class, positionId)).isZero();
        assertThat(jdbc.queryForObject("select reserved_quantity from position where id=?", Long.class, positionId)).isZero();
        assertThat(jdbc.queryForObject("select sum(quantity) from execution where account_id=? and trading_mode='INTRADAY'", Long.class, account.id))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject("select sum(amount) from ledger_entry where account_id=? and entry_type='TRADE_CREDIT'", BigDecimal.class, account.id))
                .isEqualByComparingTo("495");
        assertThat(jdbc.queryForObject("select count(*) from execution where account_id=?", Integer.class, account.id))
                .isBetween(1, 2);
    }

    @Test
    void squareOffSchedulerTimingPolicyUsesConfiguredClose() {
        assertThat(marketHours.isSquareOffWindow(BEFORE_WINDOW)).isFalse();
        assertThat(marketHours.isSquareOffWindow(WINDOW_START)).isTrue();
        assertThat(marketHours.isSessionEnded(AFTER_CLOSE)).isTrue();
        assertThat(marketHours.isSquareOffWindow(AFTER_CLOSE)).isTrue();
        assertThat(marketHours.isSquareOffWindow(Instant.parse("2026-10-03T10:00:00Z"))).isFalse();
        Account account = account(); UUID position = seedPosition(account.id, 1, 0, "100");
        assertThat(squareOff.runOnce(BEFORE_WINDOW)).isZero();
        assertPositionOpen(account.id, position, 1);
        assertThat(squareOff.runOnce(WINDOW_START)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from position where id=?", Long.class, position)).isZero();
    }

    @Test
    void configuredHolidayDoesNotSquareOffOrMutateFinancialState() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        jdbc.update("insert into market_session (id,trading_date,session_state,holiday,description,active) "
                        + "values (?,?,'HOLIDAY',true,'test holiday',true)", UUID.randomUUID(), today);
        doCallRealMethod().when(marketHours).isSquareOffWindow(any(Instant.class));
        Account account = account();
        UUID position = seedPosition(account.id, 3, 0, "100");

        assertThat(squareOff.runOnce(Instant.now())).isZero();
        assertPositionOpen(account.id, position, 3);
        assertThat(jdbc.queryForObject("select count(*) from execution where account_id=?", Integer.class, account.id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where account_id=? and entry_type<>'INITIAL_DEPOSIT'",
                Integer.class, account.id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from trading_order where account_id=?", Integer.class, account.id)).isZero();
    }

    private Account account() {
        RegistrationResponse result = registration.register(new RegistrationRequest(
                "squareoff-" + UUID.randomUUID() + "@example.invalid", PASSWORD, "Square Off Test"));
        return new Account(result.email(), result.accountId());
    }
    private UUID place(Account account, String mode, String side, long quantity) {
        return placement.placeOrder(account.email, request(side, mode, quantity), null).orderId();
    }
    private OrderPlacementRequest request(String side, String mode, long quantity) {
        return new OrderPlacementRequest("NSE", "TCS", side, "LIMIT", mode, quantity, new BigDecimal("110"));
    }
    private UUID seedPosition(UUID accountId, long quantity, long reserved, String averagePrice) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into position (id,account_id,instrument_id,trading_mode,quantity,reserved_quantity,average_price,realized_pnl,updated_at,version) values (?,?,?,'INTRADAY',?,?,?,0,?,0)",
                id, accountId, instrumentId, quantity, reserved, new BigDecimal(averagePrice), Timestamp.from(Instant.now().minusSeconds(1)));
        return id;
    }
    private void putQuote(String last, String ask, String bid, String dataStatus) {
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        Instant at = Instant.now().minusSeconds(1);
        jdbc.update("insert into market_quote (id,instrument_id,last_price,ask_price,bid_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,?,?,'OPEN',?)",
                UUID.randomUUID(), instrumentId, new BigDecimal(last), new BigDecimal(ask), new BigDecimal(bid),
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at), dataStatus);
    }
    private String orderStatus(UUID id) { return jdbc.queryForObject("select status from trading_order where id=?", String.class, id); }
    private void assertPositionOpen(UUID accountId, UUID positionId, long quantity) {
        assertThat(jdbc.queryForObject("select quantity from position where id=?", Long.class, positionId)).isEqualTo(quantity);
        assertThat(jdbc.queryForObject("select reserved_quantity from position where id=?", Long.class, positionId)).isZero();
        assertThat(jdbc.queryForObject("select available_balance from trading_account where id=?", BigDecimal.class, accountId))
                .isEqualByComparingTo("100000");
    }
    private record Account(String email, UUID id) { }
}
