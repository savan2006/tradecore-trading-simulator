package com.tradecore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradecore.alert.PriceAlertRequest;
import com.tradecore.alert.PriceAlertService;
import com.tradecore.execution.IntradaySquareOffService;
import com.tradecore.execution.OrderExecutionService;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.order.OrderCancellationService;
import com.tradecore.order.OrderPlacementRequest;
import com.tradecore.order.OrderPlacementResponse;
import com.tradecore.order.OrderPlacementService;
import com.tradecore.watchlist.WatchlistRequest;
import com.tradecore.watchlist.WatchlistService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-audit-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=audit-test",
        "tradecore.security.password=audit-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class AuditLoggingIntegrationTest {
    private static final String PASSWORD = "Audit-Test-Password-93!";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderPlacementService placement;
    @Autowired private OrderCancellationService cancellation;
    @Autowired private OrderExecutionService execution;
    @Autowired private WatchlistService watchlists;
    @Autowired private PriceAlertService alerts;
    @Autowired private IntradaySquareOffService squareOff;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private AuditLogRepository auditRepository;
    @MockitoSpyBean private MarketHoursPolicy marketHours;
    @MockitoSpyBean private MarketDataProvider provider;
    private UUID tcsId;

    @BeforeEach
    void seedFreshQuote() {
        tcsId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        jdbc.update("delete from market_quote where instrument_id=?", tcsId);
        putQuote(Instant.now());
        doReturn(true).when(marketHours).isRegularSession(any(Instant.class));
        clearInvocations(provider);
    }

    @Test
    void registrationPlacementCancellationAndExecutionCreateAppendOnlyNonSensitiveEntries() {
        Account user = account("main");
        Map<String, Object> registrationAudit = audit("USER_REGISTERED", user.userId);
        assertThat(registrationAudit.get("actor_user_id")).isEqualTo(user.userId);
        assertThat(registrationAudit.get("entity_type")).isEqualTo("USER");
        assertThat(registrationAudit.get("entity_id")).isEqualTo(user.userId);
        assertThat(String.valueOf(registrationAudit.get("metadata"))).contains(user.accountId.toString());
        assertThat(String.valueOf(registrationAudit.get("metadata"))).doesNotContain(PASSWORD);

        UUID cancelled = place(user.email, "BUY");
        assertThat(audit("ORDER_PLACED", cancelled).get("actor_user_id")).isEqualTo(user.userId);
        cancellation.cancel(user.email, cancelled);
        assertThat(audit("ORDER_CANCELLED", cancelled).get("entity_id")).isEqualTo(cancelled);

        Map<String, Object> beforeAppend = audit("ORDER_PLACED", cancelled);
        UUID filled = place(user.email, "BUY");
        assertThat(execution.executePending(filled)).isTrue();
        assertThat(audit("ORDER_EXECUTED", filled).get("actor_user_id")).isEqualTo(user.userId);
        assertThat(audit("ORDER_PLACED", cancelled)).isEqualTo(beforeAppend);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where metadata like ?", Integer.class,
                "%" + PASSWORD + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='ORDER_EXECUTED' and entity_id=?", Integer.class, filled)).isEqualTo(1);
    }

    @Test
    void priceAlertSquareOffAndAdminApiAccessAreAudited() throws Exception {
        Account user = account("actions");
        var watchlist = watchlists.create(user.email, new WatchlistRequest("Audit watchlist"));
        watchlists.add(user.email, watchlist.id(), new com.tradecore.watchlist.WatchlistItemRequest("NSE", "TCS"));
        var alert = alerts.create(user.email, new PriceAlertRequest(watchlist.id(), tcsId, "ABOVE", new BigDecimal("90")));
        assertThat(alerts.process(alert.id(), Instant.now())).isTrue();
        assertThat(audit("PRICE_ALERT_TRIGGERED", alert.id()).get("actor_user_id")).isEqualTo(user.userId);

        UUID positionId = UUID.randomUUID();
        jdbc.update("insert into position (id,account_id,instrument_id,trading_mode,quantity,reserved_quantity,average_price,realized_pnl,updated_at,version) values (?,?,?,'INTRADAY',2,0,100,0,?,0)",
                positionId, user.accountId, tcsId, Timestamp.from(Instant.now()));
        doReturn(true).when(marketHours).isSessionEnded(any(Instant.class));
        assertThat(squareOff.runOnce(Instant.now())).isEqualTo(1);
        Map<String, Object> squareOffAudit = audit("INTRADAY_SQUARE_OFF", positionId);
        assertThat(squareOffAudit.get("actor_user_id")).isEqualTo(user.userId);
        assertThat(squareOffAudit.get("entity_type")).isEqualTo("POSITION");

        jdbc.update("update app_user set role='ADMIN' where id=?", user.userId);
        mvc.perform(get("/api/v1/admin/overview").header("Authorization", basic(user.email)))
                .andExpect(status().isOk());
        Map<String, Object> adminAudit = audit("ADMIN_ACCESS_OVERVIEW", null);
        assertThat(adminAudit.get("actor_user_id")).isEqualTo(user.userId);
        assertThat(adminAudit.get("entity_type")).isEqualTo("ADMIN_API");
        assertThat(adminAudit.get("metadata")).isEqualTo(
                "{\"outcome\":\"SUCCESS\",\"details\":{\"endpoint\":\"/api/v1/admin/overview\"}}");
    }

    @Test
    void auditRepositoryFailureDoesNotRollbackExecutionOrFinancialSettlement() {
        Account user = account("audit-failure");
        UUID orderId = place(user.email, "BUY");
        doThrow(new IllegalStateException("simulated audit outage"))
                .when(auditRepository).save(any(AuditLog.class));

        assertThat(execution.executePending(orderId)).isTrue();

        assertThat(jdbc.queryForObject("select status from trading_order where id=?", String.class, orderId)).isEqualTo("FILLED");
        assertThat(jdbc.queryForObject("select count(*) from execution where order_id=?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from position where account_id=?", Long.class, user.accountId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select available_balance from trading_account where id=?", BigDecimal.class, user.accountId))
                .isEqualByComparingTo("99899.0000");
    }

    private Account account(String prefix) {
        String email = "audit-" + prefix + "-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse result = registration.register(new RegistrationRequest(email, PASSWORD, "Audit Test"));
        return new Account(email, result.userId(), result.accountId());
    }
    private UUID place(String email, String side) {
        OrderPlacementResponse result = placement.placeOrder(email,
                new OrderPlacementRequest("NSE", "TCS", side, "LIMIT", "DELIVERY", 1,
                        new BigDecimal("110")), null);
        return result.orderId();
    }
    private Map<String, Object> audit(String action, UUID targetId) {
        return jdbc.queryForMap("select actor_user_id,action,entity_type,entity_id,occurred_at,metadata from audit_log where action=? and (? is null or entity_id=?) order by occurred_at desc fetch first 1 row only",
                action, targetId, targetId);
    }
    private void putQuote(Instant providerTimestamp) {
        Instant now = Instant.now();
        jdbc.update("insert into market_quote (id,instrument_id,last_price,bid_price,ask_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,?,?,?,'LIVE')",
                UUID.randomUUID(), tcsId, new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("101"),
                Timestamp.from(providerTimestamp), Timestamp.from(providerTimestamp), Timestamp.from(now), "OPEN");
    }
    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
    private record Account(String email, UUID userId, UUID accountId) { }
}
