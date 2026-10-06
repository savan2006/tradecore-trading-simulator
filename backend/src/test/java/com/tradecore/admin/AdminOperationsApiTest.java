package com.tradecore.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradecore.execution.OrderExecutionService;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import com.tradecore.market.MarketHoursPolicy;
import com.tradecore.order.OrderPlacementRequest;
import com.tradecore.order.OrderPlacementResponse;
import com.tradecore.order.OrderPlacementService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
        "spring.datasource.url=jdbc:h2:mem:tradecore-admin-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=admin-test-static",
        "tradecore.security.password=admin-test-static-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class AdminOperationsApiTest {
    private static final String PASSWORD = "Admin-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderPlacementService placement;
    @MockitoSpyBean private MarketHoursPolicy marketHours;
    @MockitoSpyBean private MarketDataProvider provider;
    private String adminEmail;
    private String normalEmail;
    private UUID tcsId;

    @BeforeEach
    void setUp() {
        adminEmail = register("admin-ops");
        normalEmail = register("normal-ops");
        jdbc.update("update app_user set role='ADMIN' where email=?", adminEmail);
        tcsId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        jdbc.update("delete from market_quote where instrument_id=?", tcsId);
        Instant now = Instant.now().minusSeconds(1);
        jdbc.update("insert into market_quote (id,instrument_id,last_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,'OPEN','LIVE')",
                UUID.randomUUID(), tcsId, new BigDecimal("100"), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        doReturn(true).when(marketHours).isRegularSession(org.mockito.ArgumentMatchers.any(Instant.class));
        clearInvocations(provider);
    }

    @Test
    void adminEndpointsRequireAdminRoleAndReturnUnauthorizedWithoutCredentials() throws Exception {
        mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/overview").header("Authorization", basic(normalEmail)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/overview").header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk());
    }

    @Test
    void historicalBackfillIsAdminOnlyAndDoesNotChangeFinancialTables() throws Exception {
        String endpoint = "/api/v1/admin/market-data/backfill";
        mvc.perform(post(endpoint).contentType("application/json").content("{\"months\":1,\"symbols\":[\"TCS\"]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint).header("Authorization", basic(normalEmail)).contentType("application/json")
                        .content("{\"months\":1,\"symbols\":[\"TCS\"]}"))
                .andExpect(status().isForbidden());

        doReturn(java.util.List.of()).when(provider)
                .getHistoricalCandles(org.mockito.ArgumentMatchers.eq("TCS"), org.mockito.ArgumentMatchers.eq(1),
                        org.mockito.ArgumentMatchers.any(LocalDate.class));
        Map<String, java.util.List<Map<String, Object>>> before = financialSnapshot();
        String jobId = mvc.perform(post(endpoint).header("Authorization", basic(adminEmail))
                        .contentType("application/json").content("{\"months\":1,\"symbols\":[\"TCS\"]}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("QUEUED"), org.hamcrest.Matchers.is("RUNNING"), org.hamcrest.Matchers.is("COMPLETED"))))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jobId).get("jobId").asText();
        String state = "QUEUED";
        for (int attempt = 0; attempt < 100 && ("QUEUED".equals(state) || "RUNNING".equals(state)); attempt++) {
            String response = mvc.perform(get(endpoint).header("Authorization", basic(adminEmail)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var status = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
            assertThat(status.get("jobId").asText()).isEqualTo(id);
            state = status.get("state").asText();
            if ("QUEUED".equals(state) || "RUNNING".equals(state)) Thread.sleep(50);
        }
        assertThat(state).isEqualTo("COMPLETED");
        assertThat(financialSnapshot()).isEqualTo(before);
    }

    private Map<String, java.util.List<Map<String, Object>>> financialSnapshot() {
        return Map.of(
                "trading_account", jdbc.queryForList("select * from trading_account order by id"),
                "ledger_entry", jdbc.queryForList("select * from ledger_entry order by id"),
                "trading_order", jdbc.queryForList("select * from trading_order order by id"),
                "execution", jdbc.queryForList("select * from execution order by id"),
                "position", jdbc.queryForList("select * from position order by id"));
    }

    @Test
    void overviewCountsMatchPersistedRowsWithoutMutatingFinancialState() throws Exception {
        long usersBefore = jdbc.queryForObject("select count(*) from app_user", Long.class);
        long activeAccountsBefore = jdbc.queryForObject("select count(*) from trading_account where status='ACTIVE'", Long.class);
        long pendingBefore = jdbc.queryForObject("select count(*) from trading_order where status='PENDING'", Long.class);
        long filledBefore = jdbc.queryForObject("select count(*) from trading_order where status='FILLED'", Long.class);
        long cancelledBefore = jdbc.queryForObject("select count(*) from trading_order where status='CANCELLED'", Long.class);
        long positionsBefore = jdbc.queryForObject("select count(*) from position where quantity > 0", Long.class);
        long unreadBefore = jdbc.queryForObject("select count(*) from notification where read_at is null", Long.class);
        long instrumentsBefore = jdbc.queryForObject("select count(*) from instrument where tradable=true", Long.class);
        var accountBefore = jdbc.queryForMap("select available_balance,reserved_balance,version from trading_account where user_id=(select id from app_user where email=?)", adminEmail);

        mvc.perform(get("/api/v1/admin/overview").header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalUsers").value(usersBefore))
                .andExpect(jsonPath("$.pendingOrders").value(pendingBefore))
                .andExpect(jsonPath("$.filledOrders").value(filledBefore))
                .andExpect(jsonPath("$.cancelledOrders").value(cancelledBefore))
                .andExpect(jsonPath("$.openPositions").value(positionsBefore))
                .andExpect(jsonPath("$.unreadNotifications").value(unreadBefore))
                .andExpect(jsonPath("$.activeTradingAccounts").value(activeAccountsBefore))
                .andExpect(jsonPath("$.supportedInstruments").value(instrumentsBefore));

        var accountAfter = jdbc.queryForMap("select available_balance,reserved_balance,version from trading_account where user_id=(select id from app_user where email=?)", adminEmail);
        assertThat(accountAfter).isEqualTo(accountBefore);
        assertThat(jdbc.queryForObject("select count(*) from trading_order where status='PENDING'", Long.class)).isEqualTo(pendingBefore);
        verifyNoInteractions(provider);
    }

    @Test
    void userSearchAndPaginationAreBounded() throws Exception {
        mvc.perform(get("/api/v1/admin/users").param("search", "ADMIN-OPS").param("page", "0").param("size", "1")
                        .header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value(adminEmail))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/admin/users").param("size", "101").header("Authorization", basic(adminEmail)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void orderFiltersPaginationAndMarketStatusWorkWithoutProviderCalls() throws Exception {
        OrderPlacementResponse placed = placement.placeOrder(adminEmail,
                new OrderPlacementRequest("NSE", "TCS", "BUY", "LIMIT", "DELIVERY", 2, new BigDecimal("120")), null);
        mvc.perform(get("/api/v1/admin/orders").param("status", "pending").param("symbol", "tcs")
                        .param("tradingMode", "delivery").param("page", "0").param("size", "1")
                        .header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(placed.orderId().toString()))
                .andExpect(jsonPath("$.content[0].symbol").value("TCS"));
        mvc.perform(get("/api/v1/admin/orders").param("status", "PENDING").param("page", "1").param("size", "1")
                        .header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/admin/market-status").header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quoteRefreshEnabled").value(false))
                .andExpect(jsonPath("$.latestPersistedQuoteAt").isNotEmpty())
                .andExpect(jsonPath("$.orderExecutionEnabled").value(false));
        verifyNoInteractions(provider);
    }

    @Test
    void auditLogsAreAdminOnlyPagedFilteredNewestFirstAndExcludeMetadataWithoutMutation() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", basic(normalEmail)))
                .andExpect(status().isForbidden());

        jdbc.update("delete from audit_log");
        var actorId = jdbc.queryForObject("select id from app_user where email=?", UUID.class, adminEmail);
        Instant older = Instant.parse("2026-01-01T10:00:00Z");
        Instant newer = Instant.parse("2026-01-02T10:00:00Z");
        insertAudit(actorId, "TEST_FILTER_ACTION", "ORDER", UUID.randomUUID(), older,
                "{\"outcome\":\"SUCCESS\",\"details\":{\"password\":\"never-return\",\"rawMcp\":\"secret-payload\"}}");
        insertAudit(actorId, "TEST_FILTER_ACTION", "ORDER", UUID.randomUUID(), newer,
                "{\"outcome\":\"SUCCESS\",\"details\":{\"safe\":true}}");
        insertAudit(actorId, "OTHER_ACTION", "USER", UUID.randomUUID(), newer.plusSeconds(1),
                "{\"outcome\":\"FAILURE\"}");
        var accountBefore = jdbc.queryForMap("select available_balance,reserved_balance,version from trading_account where user_id=?", actorId);
        long ordersBefore = jdbc.queryForObject("select count(*) from trading_order", Long.class);

        mvc.perform(get("/api/v1/admin/audit-logs").param("action", "test_filter_action")
                        .param("actor", "admin-ops").param("targetType", "order").param("outcome", "success")
                        .param("from", "2026-01-01").param("to", "2026-01-02")
                        .param("page", "0").param("size", "1")
                        .header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].action").value("TEST_FILTER_ACTION"))
                .andExpect(jsonPath("$.content[0].actorEmail").value(adminEmail))
                .andExpect(jsonPath("$.content[0].targetType").value("ORDER"))
                .andExpect(jsonPath("$.content[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content[0].metadata").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("never-return"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-payload"))));

        mvc.perform(get("/api/v1/admin/audit-logs").param("action", "TEST_FILTER_ACTION")
                        .param("page", "0").param("size", "10")
                        .header("Authorization", basic(adminEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].occurredAt").value(newer.toString()))
                .andExpect(jsonPath("$.content[1].occurredAt").value(older.toString()));

        assertThat(jdbc.queryForMap("select available_balance,reserved_balance,version from trading_account where user_id=?", actorId))
                .isEqualTo(accountBefore);
        assertThat(jdbc.queryForObject("select count(*) from trading_order", Long.class)).isEqualTo(ordersBefore);
    }

    private void insertAudit(UUID actorId, String action, String targetType, UUID targetId, Instant at, String metadata) {
        jdbc.update("insert into audit_log(id,actor_user_id,action,entity_type,entity_id,occurred_at,metadata) values (?,?,?,?,?,?,?)",
                UUID.randomUUID(), actorId, action, targetType, targetId, Timestamp.from(at), metadata);
    }

    private String register(String prefix) {
        String email = prefix + "-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse response = registration.register(new RegistrationRequest(email, PASSWORD, "Admin Test"));
        assertThat(response.email()).isEqualTo(email);
        return email;
    }

    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
