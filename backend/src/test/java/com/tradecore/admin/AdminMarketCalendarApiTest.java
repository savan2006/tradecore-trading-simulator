package com.tradecore.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import com.tradecore.market.MarketHoursPolicy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-admin-market-calendar-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=market-calendar-admin",
        "tradecore.security.password=market-calendar-admin-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
@AutoConfigureMockMvc
class AdminMarketCalendarApiTest {
    private static final String ENDPOINT = "/api/v1/admin/market-calendar";
    private static final String PASSWORD = "Market-Calendar-Test-Password-92!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @Autowired private MarketHoursPolicy hours;
    @MockitoSpyBean private MarketDataProvider provider;
    private User admin;
    private User standard;

    @BeforeEach
    void setup() {
        jdbc.update("delete from market_session where trading_date < DATE '2026-01-01' or trading_date > DATE '2026-12-31' or trading_date=DATE '2026-11-08'");
        admin = register("calendar-admin");
        standard = register("calendar-user");
        jdbc.update("update app_user set role='ADMIN' where email=?", admin.email());
    }

    @Test
    void flywaySeedsAllOfficial2026EquityHolidaysAndPolicyClosesThem() {
        List<LocalDate> dates = List.of(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-26"),
                LocalDate.parse("2026-03-03"), LocalDate.parse("2026-03-26"), LocalDate.parse("2026-03-31"),
                LocalDate.parse("2026-04-03"), LocalDate.parse("2026-04-14"), LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-28"), LocalDate.parse("2026-06-26"), LocalDate.parse("2026-09-14"),
                LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-20"), LocalDate.parse("2026-11-10"),
                LocalDate.parse("2026-11-24"), LocalDate.parse("2026-12-25"));
        assertThat(jdbc.queryForObject("select count(*) from market_session where trading_date between DATE '2026-01-01' and DATE '2026-12-31'", Integer.class))
                .isEqualTo(16);
        for (LocalDate date : dates) {
            assertThat(hours.isRegularSession(date.atTime(10, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant()))
                    .as("holiday %s is closed", date).isFalse();
        }
        assertThat(jdbc.queryForObject("select count(*) from market_session where trading_date=DATE '2026-11-08'", Integer.class))
                .isZero();
    }

    @Test
    void adminCanCreateUpdateListAndDeactivateCalendarEntryWithoutFinancialChanges() throws Exception {
        mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ENDPOINT).header("Authorization", auth(standard.email())))
                .andExpect(status().isForbidden());
        var before = financialSnapshot();
        String response = mvc.perform(post(ENDPOINT).header("Authorization", auth(admin.email()))
                        .contentType("application/json").content(calendarBody("2027-01-01", true, null, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.holiday").value(true))
                .andExpect(jsonPath("$.active").value(true)).andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(response);
        UUID id = UUID.fromString(created.path("id").asText());
        assertThat(hours.isRegularSession(at("2027-01-01", "10:00"))).isFalse();

        mvc.perform(get(ENDPOINT).header("Authorization", auth(admin.email())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
        mvc.perform(put(ENDPOINT + "/" + id).header("Authorization", auth(admin.email()))
                        .contentType("application/json").content(calendarBody("2027-01-01", false, "10:00", "13:00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.holiday").value(false))
                .andExpect(jsonPath("$.sessionOpen").value("10:00:00"));
        assertThat(hours.isRegularSession(at("2027-01-01", "09:30"))).isFalse();
        assertThat(hours.isRegularSession(at("2027-01-01", "10:00"))).isTrue();
        assertThat(hours.isRegularSession(at("2027-01-01", "13:00"))).isFalse();
        mvc.perform(post(ENDPOINT + "/" + id + "/deactivate").header("Authorization", auth(admin.email())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(auditCount("ADMIN_MARKET_CALENDAR_CREATE", id, "SUCCESS")).isEqualTo(1);
        assertThat(auditCount("ADMIN_MARKET_CALENDAR_UPDATE", id, "SUCCESS")).isEqualTo(1);
        assertThat(auditCount("ADMIN_MARKET_CALENDAR_DEACTIVATE", id, "SUCCESS")).isEqualTo(1);
        assertThat(hours.isRegularSession(at("2027-01-01", "14:00"))).isTrue();
        assertThat(financialSnapshot()).isEqualTo(before);
        verifyNoInteractions(provider);
    }

    @Test
    void rejectsAmbiguousInvalidAndPastCalendarConfiguration() throws Exception {
        var before = financialSnapshot();
        create(calendarBody("2027-01-04", false, "10:00", null)).andExpect(status().isBadRequest());
        create(calendarBody("2027-01-04", false, "14:00", "10:00")).andExpect(status().isBadRequest());
        create(calendarBody("2020-01-01", true, null, null)).andExpect(status().isBadRequest());
        assertThat(financialSnapshot()).isEqualTo(before);
        verifyNoInteractions(provider);
    }

    @Test
    void explicitWeekendSpecialSessionCanBeConfiguredWithoutInventingSeedTimes() throws Exception {
        create(calendarBody("2026-11-08", false, "17:00", "18:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionOpen").value("17:00:00"));
        assertThat(hours.isRegularSession(at("2026-11-08", "16:59"))).isFalse();
        assertThat(hours.isRegularSession(at("2026-11-08", "17:00"))).isTrue();
    }

    @Test
    void seededPastCalendarEntriesCannotBeUpdatedOrDeactivated() throws Exception {
        UUID id = jdbc.queryForObject("select id from market_session where trading_date=DATE '2026-01-15'", UUID.class);
        mvc.perform(put(ENDPOINT + "/" + id).header("Authorization", auth(admin.email()))
                        .contentType("application/json").content(calendarBody("2026-01-15", true, null, null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ENDPOINT + "/" + id + "/deactivate").header("Authorization", auth(admin.email())))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions create(String body) throws Exception {
        return mvc.perform(post(ENDPOINT).header("Authorization", auth(admin.email()))
                .contentType("application/json").content(body));
    }

    private String calendarBody(String date, boolean holiday, String open, String close) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("tradingDate", date); body.put("holiday", holiday);
        if (open != null) body.put("sessionOpen", open);
        if (close != null) body.put("sessionClose", close);
        body.put("description", "test calendar entry");
        return mapper.writeValueAsString(body);
    }

    private List<List<Object>> financialSnapshot() {
        return List.of(
                jdbc.query("select id,available_balance,reserved_balance from trading_account order by id",
                        (rs, n) -> List.<Object>of(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3))),
                jdbc.query("select id,account_id,entry_type,amount from ledger_entry order by id",
                        (rs, n) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4))),
                jdbc.query("select id,account_id,instrument_id,quantity,reserved_quantity from position order by id",
                        (rs, n) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5))),
                jdbc.query("select id,status from trading_order order by id", (rs, n) -> List.<Object>of(rs.getString(1), rs.getString(2))),
                jdbc.query("select id,order_id,quantity,price from execution order by id",
                        (rs, n) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4))));
    }

    private int auditCount(String action, UUID targetId, String outcome) {
        return jdbc.queryForObject("select count(*) from audit_log where actor_user_id="
                + "(select id from app_user where email=?) and action=? and entity_id=? and metadata like ?",
                Integer.class, admin.email(), action, targetId, "%\"outcome\":\"" + outcome + "\"%");
    }

    private User register(String prefix) {
        String email = prefix + "-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse response = registration.register(new RegistrationRequest(email, PASSWORD, prefix));
        return new User(response.email());
    }

    private static String auth(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
    private static java.time.Instant at(String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }
    private record User(String email) { }
}
