package com.tradecore.watchlist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradecore.alert.PriceAlertRequest;
import com.tradecore.alert.PriceAlertService;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-watchlist-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=watchlist-test",
        "tradecore.security.password=watchlist-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false",
        "tradecore.alerts.processing-interval=PT1H"
})
@AutoConfigureMockMvc
class WatchlistAndPriceAlertApiTest {
    private static final String PASSWORD = "Watchlist-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @Autowired private WatchlistService watchlists;
    @Autowired private PriceAlertService alerts;
    @MockitoSpyBean private MarketDataProvider provider;
    private UUID tcsId;
    private String ownerEmail;
    private String otherEmail;

    @BeforeEach
    void setUp() {
        tcsId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        ownerEmail = account(); otherEmail = account();
        putQuote("100", "LIVE", Instant.now());
        clearInvocations(provider);
    }

    @Test
    void watchlistCrudItemsQuotesAndOwnership() throws Exception {
        String list = createWatchlist(ownerEmail, "First");
        mvc.perform(post("/api/v1/watchlists/{id}/items", list).header("Authorization", basic(ownerEmail))
                        .contentType("application/json").content("{\"exchange\":\"NSE\",\"symbol\":\"TCS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].symbol").value("TCS"))
                .andExpect(jsonPath("$.items[0].quote.lastPrice").value(100.0))
                .andExpect(jsonPath("$.items[0].quote.dataStatus").value("LIVE"));
        mvc.perform(post("/api/v1/watchlists/{id}/items", list).header("Authorization", basic(ownerEmail))
                        .contentType("application/json").content("{\"exchange\":\"NSE\",\"symbol\":\"TCS\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/watchlists").header("Authorization", basic(ownerEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("First"));
        mvc.perform(get("/api/v1/watchlists/{id}", list).header("Authorization", basic(otherEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/watchlists/{id}", list).header("Authorization", basic(otherEmail))
                        .contentType("application/json").content("{\"name\":\"Hijack\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/watchlists/{id}/items", list).header("Authorization", basic(otherEmail))
                        .contentType("application/json").content("{\"exchange\":\"NSE\",\"symbol\":\"TCS\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/watchlists/{id}", list).header("Authorization", basic(ownerEmail))
                        .contentType("application/json").content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
        mvc.perform(delete("/api/v1/watchlists/{id}/items/TCS", list).header("Authorization", basic(ownerEmail)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/watchlists/{id}", list).header("Authorization", basic(ownerEmail)))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(delete("/api/v1/watchlists/{id}", list).header("Authorization", basic(otherEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/watchlists/{id}", list).header("Authorization", basic(ownerEmail)))
                .andExpect(status().isNoContent());
        verifyNoInteractions(provider);
    }

    @Test
    void alertCreationIsOwnedAndPreventsDuplicateActiveAlerts() throws Exception {
        UUID watchlist = UUID.fromString(createWatchlist(ownerEmail, "Alerts"));
        addTcs(ownerEmail, watchlist);
        var request = new PriceAlertRequest(watchlist, tcsId, "ABOVE", new BigDecimal("110"));
        var created = alerts.create(ownerEmail, request);
        assertThat(created.active()).isTrue();
        mvc.perform(get("/api/v1/alerts").header("Authorization", basic(ownerEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(created.id().toString()));
        mvc.perform(get("/api/v1/alerts").header("Authorization", basic(otherEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> alerts.create(otherEmail, request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e -> ((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> alerts.create(ownerEmail, request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e -> ((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409);
        mvc.perform(delete("/api/v1/alerts/{id}", created.id()).header("Authorization", basic(otherEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/alerts/{id}", created.id()).header("Authorization", basic(ownerEmail)))
                .andExpect(status().isNoContent());
    }

    @Test
    void aboveAndBelowRulesIgnoreNonTriggersStaleAndMissingQuotesAndNotifyOnce() throws Exception {
        UUID watchlist = watchlistWithTcs(ownerEmail, "Trigger tests");
        var above = alerts.create(ownerEmail, new PriceAlertRequest(watchlist, tcsId, "ABOVE", new BigDecimal("105")));
        var below = alerts.create(ownerEmail, new PriceAlertRequest(watchlist, tcsId, "BELOW", new BigDecimal("95")));
        Instant now = Instant.now().plusSeconds(1);
        assertThat(alerts.process(above.id(), now)).isFalse();
        putQuote("100", "STALE", Instant.now());
        assertThat(alerts.process(above.id(), now)).isFalse();
        assertThat(alerts.process(below.id(), now)).isFalse();
        assertThat(notificationCount(ownerEmail)).isZero();
        putQuote("110", "LIVE", Instant.now());
        assertThat(alerts.process(above.id(), now)).isTrue();
        assertThat(alerts.process(above.id(), now)).isFalse();
        assertThat(notificationCount(ownerEmail)).isEqualTo(1);
        assertThat(alertActive(above.id())).isFalse();
        assertThat(alertActive(below.id())).isTrue();

        var missingQuote = alerts.create(ownerEmail,
                new PriceAlertRequest(watchlist, tcsId, "BELOW", new BigDecimal("120")));
        jdbc.update("delete from market_quote where instrument_id=?", tcsId);
        assertThat(alerts.process(missingQuote.id(), now)).isFalse();
        assertThat(notificationCount(ownerEmail)).isEqualTo(1);
        verifyNoInteractions(provider);
    }

    @Test
    void belowAlertTriggersAndConcurrentProcessingCreatesOneNotification() throws Exception {
        UUID watchlist = watchlistWithTcs(ownerEmail, "Concurrency");
        var alert = alerts.create(ownerEmail,
                new PriceAlertRequest(watchlist, tcsId, "BELOW", new BigDecimal("100")));
        putQuote("90", "LIVE", Instant.now());
        Instant now = Instant.now().plusSeconds(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Boolean> results = pool.invokeAll(java.util.Collections.nCopies(6,
                    (java.util.concurrent.Callable<Boolean>) () -> alerts.process(alert.id(), now)))
                    .stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).toList();
            assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        assertThat(notificationCount(ownerEmail)).isEqualTo(1);
        assertThat(alertActive(alert.id())).isFalse();
        verifyNoInteractions(provider);
    }

    @Test
    void unauthenticatedEndpointsReturn401() throws Exception {
        mvc.perform(get("/api/v1/watchlists")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/watchlists").contentType("application/json").content("{\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized());
    }

    private UUID watchlistWithTcs(String email, String name) throws Exception {
        UUID id = UUID.fromString(createWatchlist(email, name)); addTcs(email, id); return id;
    }
    private void addTcs(String email, UUID watchlist) throws Exception {
        mvc.perform(post("/api/v1/watchlists/{id}/items", watchlist).header("Authorization", basic(email))
                .contentType("application/json").content("{\"exchange\":\"NSE\",\"symbol\":\"TCS\"}"))
                .andExpect(status().isOk());
    }
    private String createWatchlist(String email, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/watchlists").header("Authorization", basic(email))
                .contentType("application/json").content(mapper.writeValueAsString(new WatchlistRequest(name))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body); return json.get("id").asText();
    }
    private String account() {
        return registration.register(new RegistrationRequest("watchlist-" + UUID.randomUUID() + "@example.invalid",
                PASSWORD, "Watchlist Test")).email();
    }
    private void putQuote(String price, String dataStatus, Instant at) {
        jdbc.update("delete from market_quote where instrument_id=?", tcsId);
        jdbc.update("insert into market_quote (id,instrument_id,last_price,previous_close,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,? ,?,'OPEN',?)",
                UUID.randomUUID(), tcsId, new BigDecimal(price), new BigDecimal("90"), Timestamp.from(at),
                Timestamp.from(at), Timestamp.from(at), dataStatus);
    }
    private int notificationCount(String email) {
        return jdbc.queryForObject("select count(*) from notification n join app_user u on u.id=n.user_id where u.email=? and n.notification_type='PRICE_ALERT'", Integer.class, email);
    }
    private boolean alertActive(UUID id) { return jdbc.queryForObject("select active from price_alert where id=?", Boolean.class, id); }
    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
