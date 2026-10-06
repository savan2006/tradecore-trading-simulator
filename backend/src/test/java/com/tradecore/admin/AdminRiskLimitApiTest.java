package com.tradecore.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
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
        "spring.datasource.url=jdbc:h2:mem:tradecore-admin-risk-limits-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=admin-risk-static",
        "tradecore.security.password=admin-risk-static-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class AdminRiskLimitApiTest {
    private static final String PASSWORD = "Admin-Risk-Test-Password-93!";
    private static final String ENDPOINT = "/api/v1/admin/risk-limits";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @MockitoSpyBean private MarketDataProvider provider;
    private TestAccount admin;
    private TestAccount user;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from risk_limit");
        admin = register("risk-admin");
        user = register("risk-user");
        jdbc.update("update app_user set role='ADMIN' where email=?", admin.email());
        var instrumentId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        Instant now = Instant.now();
        jdbc.update("insert into market_quote (id,instrument_id,last_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,'OPEN','LIVE')",
                UUID.randomUUID(), instrumentId, new BigDecimal("125"), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        clearInvocations(provider);
    }

    @Test
    void adminCreatesUpdatesActivatesAndDeactivatesAccountLimitWithoutFinancialChanges() throws Exception {
        mvc.perform(post(ENDPOINT).contentType("application/json").content(accountLimit(user.accountId(), "MAX_ORDER_AMOUNT", "150", false)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ENDPOINT).header("Authorization", basic(user.email())))
                .andExpect(status().isForbidden());
        mvc.perform(get(ENDPOINT).header("Authorization", basic(admin.email())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        Map<String, List<Map<String, Object>>> before = financialSnapshot();
        String body = mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email()))
                        .contentType("application/json").content(accountLimit(user.accountId(), "MAX_ORDER_AMOUNT", "150", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("ACCOUNT"))
                .andExpect(jsonPath("$.accountId").value(user.accountId().toString()))
                .andExpect(jsonPath("$.accountEmail").value(user.email()))
                .andExpect(jsonPath("$.enabled").value(false)).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(body).get("id").asText());
        assertThat(financialSnapshot()).isEqualTo(before);

        mvc.perform(put(ENDPOINT + "/" + id).header("Authorization", basic(admin.email()))
                        .contentType("application/json").content(accountLimit(user.accountId(), "MAX_ORDER_AMOUNT", "250", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configuredValue").value(250));
        mvc.perform(post(ENDPOINT + "/" + id + "/activate").header("Authorization", basic(admin.email())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get(ENDPOINT).header("Authorization", basic(admin.email())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].effectiveNow").value(true));
        mvc.perform(post(ENDPOINT + "/" + id + "/deactivate").header("Authorization", basic(admin.email())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        assertThat(financialSnapshot()).isEqualTo(before);
        verifyNoInteractions(provider);
    }

    @Test
    void enforcesAccountAndInstrumentScopesAndRejectsOverlaps() throws Exception {
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content(accountLimit(user.accountId(), "MAX_ORDER_QUANTITY", "10", true)))
                .andExpect(status().isOk());
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content(accountLimit(user.accountId(), "MAX_ORDER_QUANTITY", "20", true)))
                .andExpect(status().isConflict());
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content(instrumentLimit("INSTRUMENT_BLOCKED", "1", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("INSTRUMENT"))
                .andExpect(jsonPath("$.symbol").value("TCS"));
        verifyNoInteractions(provider);
    }

    @Test
    void validatesTypesValuesAccountsDatesAndSymbols() throws Exception {
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content(accountLimit(user.accountId(), "NEW_RISK_RULE", "10", true)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content(accountLimit(user.accountId(), "MAX_ORDER_AMOUNT", "0", true)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content(accountLimit(UUID.randomUUID(), "MAX_ORDER_AMOUNT", "10", true)))
                .andExpect(status().isNotFound());
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content("{\"scope\":\"INSTRUMENT\",\"limitType\":\"INSTRUMENT_BLOCKED\",\"symbol\":\"NO_SUCH_SYMBOL\",\"configuredValue\":1}"))
                .andExpect(status().isBadRequest());
        Map<String, Object> invalidDates = new HashMap<>();
        invalidDates.put("scope", "ACCOUNT"); invalidDates.put("limitType", "MAX_ORDER_AMOUNT");
        invalidDates.put("accountId", user.accountId()); invalidDates.put("configuredValue", 100);
        invalidDates.put("effectiveFrom", "2026-01-02T00:00:00Z"); invalidDates.put("effectiveUntil", "2026-01-01T00:00:00Z");
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content(mapper.writeValueAsString(invalidDates)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(provider);
    }

    @Test
    void normalUserIsForbiddenAndConfiguredAccountLimitIsUsedForOrderValidation() throws Exception {
        mvc.perform(post(ENDPOINT).header("Authorization", basic(user.email())).contentType("application/json")
                        .content(accountLimit(user.accountId(), "MAX_ORDER_AMOUNT", "150", true)))
                .andExpect(status().isForbidden());
        mvc.perform(post(ENDPOINT).header("Authorization", basic(admin.email())).contentType("application/json")
                        .content(accountLimit(user.accountId(), "MAX_ORDER_AMOUNT", "150", true)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders").header("Authorization", basic(user.email()))
                        .contentType("application/json").content("{\"exchange\":\"NSE\",\"symbol\":\"TCS\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"tradingMode\":\"DELIVERY\",\"quantity\":2,\"limitPrice\":100}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select available_balance from trading_account where id=?", BigDecimal.class, user.accountId()))
                .isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("select count(*) from trading_order where account_id=?", Integer.class, user.accountId())).isZero();
        verifyNoInteractions(provider);
    }

    private TestAccount register(String prefix) {
        String email = prefix + "-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse result = registration.register(new RegistrationRequest(email, PASSWORD, "Risk Admin Test"));
        return new TestAccount(email, result.accountId());
    }

    private String accountLimit(UUID accountId, String type, String value, boolean enabled) throws Exception {
        return mapper.writeValueAsString(Map.of("scope", "ACCOUNT", "limitType", type, "accountId", accountId,
                "configuredValue", new BigDecimal(value), "enabled", enabled));
    }

    private String instrumentLimit(String type, String value, boolean enabled) throws Exception {
        return mapper.writeValueAsString(Map.of("scope", "INSTRUMENT", "limitType", type, "exchange", "NSE",
                "symbol", "TCS", "configuredValue", new BigDecimal(value), "enabled", enabled));
    }

    private Map<String, List<Map<String, Object>>> financialSnapshot() {
        return Map.of(
                "trading_account", jdbc.queryForList("select * from trading_account order by id"),
                "ledger_entry", jdbc.queryForList("select * from ledger_entry order by id"),
                "trading_order", jdbc.queryForList("select * from trading_order order by id"),
                "execution", jdbc.queryForList("select * from execution order by id"),
                "position", jdbc.queryForList("select * from position order by id"));
    }

    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private record TestAccount(String email, UUID accountId) {}
}
