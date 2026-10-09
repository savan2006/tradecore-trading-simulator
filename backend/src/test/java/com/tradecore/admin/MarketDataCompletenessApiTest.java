package com.tradecore.admin;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-completeness-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=completeness-test",
        "tradecore.security.password=completeness-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class MarketDataCompletenessApiTest {
    private static final String PASSWORD = "Completeness-Test-Password-91!";
    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @MockitoSpyBean private MarketDataProvider provider;
    private String adminEmail;
    private String userEmail;

    @BeforeEach
    void setup() {
        jdbc.update("delete from market_candle");
        jdbc.update("update instrument set tradable = (symbol in ('TCS','RELIANCE')) where exchange='NSE'");
        adminEmail = register("completeness-admin").email();
        userEmail = register("completeness-user").email();
        jdbc.update("update app_user set role='ADMIN' where email=?", adminEmail);
    }

    @Test
    void completenessEndpointIsAdminOnlyAndReportsCountsAndGapsFromStoredRows() throws Exception {
        String endpoint = "/api/v1/admin/market-data/completeness";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).header("Authorization", auth(userEmail))).andExpect(status().isForbidden());

        LocalDate through = LocalDate.now(NSE_ZONE).minusDays(1);
        LocalDate from = through.minusMonths(1);
        List<LocalDate> expected = new ArrayList<>();
        int weekdaysInRange = 0;
        for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                weekdaysInRange++;
                if (jdbc.queryForObject("select count(*) from market_session where trading_date=? and active=true and holiday=true",
                        Integer.class, date) == 0) {
                    expected.add(date);
                }
            }
        }
        assertThat(expected).hasSizeGreaterThan(2);
        UUID tcsId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        for (LocalDate date : expected.subList(expected.size() - 2, expected.size())) {
            InstantData.insert(jdbc, tcsId, date);
        }

        mvc.perform(get(endpoint).param("months", "1").header("Authorization", auth(adminEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentsTotal").value(2))
                .andExpect(jsonPath("$.instrumentsWithAnyData").value(1))
                .andExpect(jsonPath("$.instrumentsWithoutData").value(1))
                .andExpect(jsonPath("$.instrumentsLikelyComplete").value(0))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.containsString("possible gaps")))
                .andExpect(jsonPath("$.instruments[0].symbol").value("RELIANCE"))
                .andExpect(jsonPath("$.instruments[0].candleCount").value(0))
                .andExpect(jsonPath("$.instruments[0].latestCandleIsStale").value(true))
                .andExpect(jsonPath("$.instruments[1].symbol").value("TCS"))
                .andExpect(jsonPath("$.instruments[1].candleCount").value(2))
                .andExpect(jsonPath("$.instruments[1].firstDate").value(expected.get(expected.size() - 2).toString()))
                .andExpect(jsonPath("$.instruments[1].lastDate").value(expected.getLast().toString()))
                .andExpect(jsonPath("$.instruments[1].weekdaysInRange").value(weekdaysInRange))
                .andExpect(jsonPath("$.instruments[1].candlesExpected").value(expected.size()))
                .andExpect(jsonPath("$.instruments[1].missingWeekdaysCount").value(expected.size() - 2))
                .andExpect(jsonPath("$.instruments[1].missingDates.length()").value(Math.min(20, expected.size() - 2)))
                .andExpect(jsonPath("$.instruments[1].latestCandleIsStale").value(false));
        verifyNoInteractions(provider);
    }

    @Test
    void monthsMustBeWithinOneAndThirtySix() throws Exception {
        mvc.perform(get("/api/v1/admin/market-data/completeness").param("months", "37")
                        .header("Authorization", auth(adminEmail)))
                .andExpect(status().isBadRequest());
    }

    private RegistrationResponse register(String prefix) {
        return registration.register(new RegistrationRequest(prefix + "-" + UUID.randomUUID() + "@example.invalid",
                PASSWORD, prefix));
    }

    private static String auth(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static class InstantData {
        static void insert(JdbcTemplate jdbc, UUID instrumentId, LocalDate date) {
            var bucket = date.atStartOfDay(NSE_ZONE).toInstant();
            jdbc.update("insert into market_candle (id,instrument_id,resolution,bucket_start,open_price,high_price,low_price,close_price,volume,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), instrumentId, "1D", Timestamp.from(bucket), new BigDecimal("100"),
                    new BigDecimal("110"), new BigDecimal("90"), new BigDecimal("105"), 10L,
                    Timestamp.from(java.time.Instant.now()));
        }
    }
}
