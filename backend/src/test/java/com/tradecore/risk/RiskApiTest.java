package com.tradecore.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-risk-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=risk-test-static",
        "tradecore.security.password=risk-test-static-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class RiskApiTest {
    private static final String PASSWORD = "Risk-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @MockitoSpyBean private MarketDataProvider provider;
    private TestAccount owner;
    private TestAccount other;
    private UUID tcsId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from risk_limit");
        owner = account("risk-owner");
        other = account("risk-other");
        tcsId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        clearInvocations(provider);
    }

    @Test
    void returnsOnlyApplicableGlobalInstrumentAndOwnAccountLimits() throws Exception {
        insertLimit("GLOBAL", null, null, "MAX_ORDER_AMOUNT", "10000");
        insertLimit("ACCOUNT", owner.accountId(), null, "MAX_ORDER_QUANTITY", "15");
        insertLimit("ACCOUNT", other.accountId(), null, "TRADING_DISABLED", "1");
        insertLimit("INSTRUMENT", null, tcsId, "INSTRUMENT_BLOCKED", "1");
        insertExpiredLimit(owner.accountId());

        mvc.perform(get("/api/v1/risk/me").header("Authorization", basic(owner.email())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].scope").value("ACCOUNT"))
                .andExpect(jsonPath("$[0].limitType").value("MAX_ORDER_QUANTITY"))
                .andExpect(jsonPath("$[0].configuredValue").value(15))
                .andExpect(jsonPath("$[0].currentUsage").value(nullValue()))
                .andExpect(jsonPath("$[0].remainingValue").value(nullValue()))
                .andExpect(jsonPath("$[1].scope").value("GLOBAL"))
                .andExpect(jsonPath("$[1].configuredValue").value(10000))
                .andExpect(jsonPath("$[2].scope").value("INSTRUMENT"))
                .andExpect(jsonPath("$[2].symbol").value("TCS"))
                .andExpect(jsonPath("$.length()").value(3));
        verifyNoInteractions(provider);
    }

    @Test
    void returnsEmptyArrayWhenNoRiskLimitsAreConfigured() throws Exception {
        mvc.perform(get("/api/v1/risk/me").header("Authorization", basic(owner.email())))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
        verifyNoInteractions(provider);
    }

    @Test
    void endpointRequiresAuthenticationAndDoesNotMutateFinancialData() throws Exception {
        mvc.perform(get("/api/v1/risk/me")).andExpect(status().isUnauthorized());
        var before = jdbc.queryForMap("select available_balance,reserved_balance,version from trading_account where id=?",
                owner.accountId());
        int orderCount = jdbc.queryForObject("select count(*) from trading_order where account_id=?", Integer.class,
                owner.accountId());
        int ledgerCount = jdbc.queryForObject("select count(*) from ledger_entry where account_id=?", Integer.class,
                owner.accountId());

        mvc.perform(get("/api/v1/risk/me").header("Authorization", basic(owner.email())))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForMap("select available_balance,reserved_balance,version from trading_account where id=?",
                owner.accountId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from trading_order where account_id=?", Integer.class,
                owner.accountId())).isEqualTo(orderCount);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where account_id=?", Integer.class,
                owner.accountId())).isEqualTo(ledgerCount);
        verifyNoInteractions(provider);
    }

    private TestAccount account(String prefix) {
        String email = prefix + "-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse result = registration.register(new RegistrationRequest(email, PASSWORD, "Risk Test"));
        return new TestAccount(email, result.accountId());
    }

    private void insertLimit(String scope, UUID accountId, UUID instrumentId, String type, String value) {
        jdbc.update("insert into risk_limit (id,account_id,instrument_id,scope,limit_type,limit_value,enabled,created_at) "
                        + "values (?,?,?,?,?,?,true,?)",
                UUID.randomUUID(), accountId, instrumentId, scope, type, new BigDecimal(value), Timestamp.from(Instant.now()));
    }

    private void insertExpiredLimit(UUID accountId) {
        jdbc.update("insert into risk_limit (id,account_id,scope,limit_type,limit_value,enabled,effective_until,created_at) "
                        + "values (?,?,'ACCOUNT','MAX_ORDER_AMOUNT',500,true,?,?)",
                UUID.randomUUID(), accountId, Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now()));
    }

    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private record TestAccount(String email, UUID accountId) { }
}
