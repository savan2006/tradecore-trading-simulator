package com.tradecore.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradecore.execution.IntradaySquareOffService;
import com.tradecore.execution.OrderExecutionService;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.market.MarketDataProvider;
import com.tradecore.order.OrderCancellationService;
import com.tradecore.order.OrderModificationRequest;
import com.tradecore.order.OrderPlacementRequest;
import com.tradecore.order.OrderPlacementService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-reconciliation-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=reconciliation-test",
        "tradecore.security.password=reconciliation-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class ReconciliationApiTest {
    private static final String PASSWORD = "Reconciliation-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderPlacementService placement;
    @Autowired private OrderCancellationService cancellation;
    @Autowired private OrderExecutionService execution;
    @Autowired private IntradaySquareOffService squareOff;
    @Autowired private ReconciliationService reconciliation;
    @MockitoSpyBean private MarketHoursPolicy marketHours;
    @MockitoSpyBean private MarketDataProvider provider;
    private String adminEmail;
    private String userEmail;
    private UUID instrumentId;

    @BeforeEach
    void setUp() {
        adminEmail = register("reconciliation-admin").email();
        userEmail = register("reconciliation-user").email();
        jdbc.update("update app_user set role='ADMIN' where email=?", adminEmail);
        instrumentId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        putFreshQuote();
        doReturn(true).when(marketHours).isRegularSession(any(Instant.class));
        doReturn(true).when(marketHours).isSquareOffWindow(any(Instant.class));
        clearInvocations(provider);
    }

    @Test
    void adminEndpointIsReadOnlyAndRestrictedToAdministrators() throws Exception {
        String endpoint = "/api/v1/admin/reconciliation";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).header("Authorization", basic(userEmail))).andExpect(status().isForbidden());

        int auditRowsBefore = jdbc.queryForObject("select count(*) from audit_log", Integer.class);
        mvc.perform(get(endpoint).header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PASS"))
                .andExpect(jsonPath("$[0].violationCount").value(0));
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(auditRowsBefore);
        verifyNoInteractions(provider);
        assertAllPass();
    }

    @Test
    void fixedSeedFinancialServiceSequenceKeepsEveryInvariant() {
        UUID accountId = accountId(userEmail);
        Random random = new Random(0xC4_2026L);

        for (int iteration = 0; iteration < 5; iteration++) {
            long deliveryQuantity = random.nextInt(3) + 1;
            var modifiableBuy = place(userEmail, "BUY", "LIMIT", "DELIVERY", deliveryQuantity, "90");
            placement.modifyPendingOrder(userEmail, modifiableBuy.orderId(),
                    new OrderModificationRequest(deliveryQuantity, new BigDecimal("101"), null));
            assertThat(execution.executePending(modifiableBuy.orderId())).isTrue();

            var cancelledBuy = place(userEmail, "BUY", "LIMIT", "DELIVERY", random.nextInt(2) + 1, "1");
            cancellation.cancel(userEmail, cancelledBuy.orderId());

            long intradayQuantity = random.nextInt(2) + 2;
            var intradayBuy = place(userEmail, "BUY", "MARKET", "INTRADAY", intradayQuantity, null);
            assertThat(execution.executePending(intradayBuy.orderId())).isTrue();
            var pendingSell = place(userEmail, "SELL", "LIMIT", "INTRADAY", 1, "200");
            placement.modifyPendingOrder(userEmail, pendingSell.orderId(),
                    new OrderModificationRequest(2L, new BigDecimal("200"), null));
            cancellation.cancel(userEmail, pendingSell.orderId());
            squareOff.runOnce(Instant.now());
        }

        assertAllPass();
        assertThat(jdbc.queryForObject("select count(*) from execution where account_id=?", Integer.class, accountId))
                .isGreaterThan(10);
    }

    @Test
    void reportsAccountCashLedgerMismatchWithItsSampleId() {
        UUID accountId = accountId(userEmail);
        jdbc.update("update trading_account set available_balance=available_balance + 1 where id=?", accountId);

        try {
            ReconciliationService.Check check = reconciliation.reconcile().stream()
                    .filter(result -> result.name().equals("account_cash_matches_ledger"))
                    .findFirst().orElseThrow();
            assertThat(check.status()).isEqualTo("FAIL");
            assertThat(check.violationCount()).isEqualTo(1);
            assertThat(check.sampleIds()).contains(accountId);
        } finally {
            jdbc.update("update trading_account set available_balance=available_balance - 1 where id=?", accountId);
        }
        assertAllPass();
    }

    private RegistrationResponse register(String prefix) {
        return registration.register(new RegistrationRequest(prefix + "-" + UUID.randomUUID() + "@example.invalid",
                PASSWORD, "Reconciliation Test"));
    }

    private UUID accountId(String email) {
        return jdbc.queryForObject("select id from trading_account where user_id=(select id from app_user where email=?)",
                UUID.class, email);
    }

    private com.tradecore.order.OrderPlacementResponse place(String email, String side, String type,
            String mode, long quantity, String limit) {
        return placement.placeOrder(email, new OrderPlacementRequest("NSE", "TCS", side, type, mode,
                quantity, limit == null ? null : new BigDecimal(limit)), null);
    }

    private void putFreshQuote() {
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        Instant at = Instant.now().minusSeconds(1);
        jdbc.update("insert into market_quote (id,instrument_id,last_price,bid_price,ask_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,?,?,'UNKNOWN','LIVE')",
                UUID.randomUUID(), instrumentId, new BigDecimal("100"), new BigDecimal("99"),
                new BigDecimal("101"), Timestamp.from(at), Timestamp.from(at), Timestamp.from(Instant.now()));
    }

    private void assertAllPass() {
        List<ReconciliationService.Check> checks = reconciliation.reconcile();
        assertThat(checks).isNotEmpty().allSatisfy(check -> {
            assertThat(check.status()).as(check.name()).isEqualTo("PASS");
            assertThat(check.violationCount()).as(check.name()).isZero();
            assertThat(check.sampleIds()).as(check.name()).isEmpty();
        });
    }

    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD)
                .getBytes(StandardCharsets.UTF_8));
    }
}
