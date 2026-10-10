package com.tradecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tradecore.alert.PriceAlertService;
import com.tradecore.execution.OrderExecutionService;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.market.MarketDataProvider;
import com.tradecore.market.MarketHoursPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-flow-smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=flow-smoke-static",
        "tradecore.security.password=flow-smoke-static-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false",
        "tradecore.market-data.scheduling.regular-session-open=00:00",
        "tradecore.market-data.scheduling.regular-session-close=23:59:59"
})
class TradeCoreFlowSmokeTest {
    private static final String PASSWORD = "Synthetic-Smoke-Password-41!";
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderExecutionService execution;
    @Autowired private PriceAlertService alerts;
    @MockitoSpyBean private MarketDataProvider provider;
    @MockitoSpyBean private MarketHoursPolicy marketHours;
    private UUID instrumentId;

    @BeforeEach
    void seedOnePersistedSyntheticQuote() {
        instrumentId = jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol='TCS'", UUID.class);
        jdbc.update("delete from market_quote where instrument_id=?", instrumentId);
        Instant now = Instant.now().minusSeconds(1);
        jdbc.update("insert into market_quote (id,instrument_id,last_price,market_at,provider_updated_at,received_at,market_status,data_status) "
                        + "values (?,?,?,?,?,?,'OPEN','LIVE')",
                UUID.randomUUID(), instrumentId, new BigDecimal("100"), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        doReturn(true).when(marketHours).isRegularSession(any(Instant.class));
        clearInvocations(provider);
    }

    @Test
    void syntheticUserCompletesMainAndAdminFlowsThroughHttpApis() throws Exception {
        String email = "phase10a-" + UUID.randomUUID() + "@example.invalid";
        ResponseEntity<String> registrationResult = request(HttpMethod.POST, "/api/v1/auth/register", null,
                new RegistrationRequest(email, PASSWORD, "Phase 10A Smoke"));
        assertThat(registrationResult.getStatusCode().value()).isEqualTo(201);
        RegistrationResponse created = mapper.readValue(registrationResult.getBody(), RegistrationResponse.class);
        assertThat(created.email()).isEqualTo(email);

        assertStatus(HttpMethod.POST, "/api/v1/auth/register", null,
                new RegistrationRequest(email, PASSWORD, "Duplicate"), 409);
        assertStatus(HttpMethod.GET, "/api/v1/portfolio/me", "missing-credential", null, 401);
        assertStatus(HttpMethod.GET, "/api/v1/admin/overview", email, null, 403);
        assertThat(json(HttpMethod.GET, "/api/v1/market/instruments?query=TCS", email, null).isArray()).isTrue();
        JsonNode quotes = json(HttpMethod.GET, "/api/v1/market/quotes?symbols=TCS", email, null);
        assertThat(quotes.get(0).path("dataStatus").asText()).isEqualTo("LIVE");
        assertThat(json(HttpMethod.GET, "/api/v1/learning/companies/TCS/overview", email, null)
                .path("learningProfile").path("symbol").asText()).isEqualTo("TCS");

        JsonNode initialPortfolio = json(HttpMethod.GET, "/api/v1/portfolio/me", email, null);
        assertThat(initialPortfolio.path("positionCount").asInt()).isZero();
        json(HttpMethod.GET, "/api/v1/orders?page=0&size=5", email, null);
        json(HttpMethod.GET, "/api/v1/watchlists", email, null);
        json(HttpMethod.GET, "/api/v1/notifications/unread-count", email, null);
        json(HttpMethod.GET, "/api/v1/risk/me", email, null);

        JsonNode createdWatchlist = json(HttpMethod.POST, "/api/v1/watchlists", email,
                java.util.Map.of("name", "Synthetic Smoke"));
        String watchlistId = createdWatchlist.path("id").asText();
        JsonNode withItem = json(HttpMethod.POST, "/api/v1/watchlists/" + watchlistId + "/items", email,
                java.util.Map.of("exchange", "NSE", "symbol", "TCS"));
        assertThat(withItem.path("items").get(0).path("symbol").asText()).isEqualTo("TCS");
        assertThat(withItem.path("items").get(0).path("quote").path("dataStatus").asText()).isEqualTo("LIVE");

        JsonNode createdAlert = json(HttpMethod.POST, "/api/v1/alerts", email,
                java.util.Map.of("watchlistId", watchlistId, "instrumentId", instrumentId.toString(),
                        "condition", "ABOVE", "targetPrice", 90));
        assertThat(createdAlert.path("active").asBoolean()).isTrue();
        assertThat(json(HttpMethod.GET, "/api/v1/alerts", email, null).size()).isEqualTo(1);
        assertThat(alerts.process(UUID.fromString(createdAlert.path("id").asText()), Instant.now())).isTrue();
        JsonNode unread = json(HttpMethod.GET, "/api/v1/notifications/unread-count", email, null);
        assertThat(unread.path("unreadCount").asInt()).isEqualTo(1);
        JsonNode notificationPage = json(HttpMethod.GET, "/api/v1/notifications?page=0&size=10&unreadOnly=true", email, null);
        assertThat(notificationPage.path("items").get(0).path("notificationType").asText()).isEqualTo("PRICE_ALERT");
        String notificationId = notificationPage.path("items").get(0).path("id").asText();
        assertThat(json(HttpMethod.POST, "/api/v1/notifications/" + notificationId + "/read", email, null)
                .path("readAt").isNull()).isFalse();
        json(HttpMethod.POST, "/api/v1/notifications/read-all", email, null);
        assertThat(json(HttpMethod.GET, "/api/v1/notifications/unread-count", email, null)
                .path("unreadCount").asInt()).isZero();

        JsonNode cancelled = json(HttpMethod.POST, "/api/v1/orders", email, orderRequest());
        String cancelledOrderId = cancelled.path("orderId").asText();
        assertStatus(HttpMethod.POST, "/api/v1/orders/" + cancelledOrderId + "/cancel", email, null, 200);
        assertThat(json(HttpMethod.GET, "/api/v1/orders/" + cancelledOrderId, email, null)
                .path("status").asText()).isEqualTo("CANCELLED");

        JsonNode placedForExecution = json(HttpMethod.POST, "/api/v1/orders", email, orderRequest());
        UUID pendingOrderId = UUID.fromString(placedForExecution.path("orderId").asText());
        assertThat(execution.executePending(pendingOrderId)).isTrue();
        JsonNode orders = json(HttpMethod.GET, "/api/v1/orders?status=FILLED&symbol=TCS&page=0&size=20", email, null);
        assertThat(orders.path("content").get(0).path("status").asText()).isEqualTo("FILLED");
        assertThat(json(HttpMethod.GET, "/api/v1/trades?page=0&size=10", email, null)
                .path("content").get(0).path("symbol").asText()).isEqualTo("TCS");
        JsonNode portfolio = json(HttpMethod.GET, "/api/v1/portfolio/me", email, null);
        assertThat(portfolio.path("positionCount").asInt()).isEqualTo(1);
        assertThat(portfolio.path("positions").get(0).path("quantity").asLong()).isEqualTo(1);
        assertThat(portfolio.path("availableBalance").decimalValue()).isEqualByComparingTo("99900.0000");

        jdbc.update("update app_user set role='ADMIN' where email=?", email);
        JsonNode overview = json(HttpMethod.GET, "/api/v1/admin/overview", email, null);
        assertThat(overview.path("totalUsers").asLong()).isPositive();
        assertThat(json(HttpMethod.GET, "/api/v1/admin/users?search=phase10a-&page=0&size=5", email, null)
                .path("content").get(0).path("email").asText()).isEqualTo(email);
        assertThat(json(HttpMethod.GET, "/api/v1/admin/orders?status=FILLED&symbol=TCS&page=0&size=5", email, null)
                .path("content").get(0).path("status").asText()).isEqualTo("FILLED");
        assertThat(json(HttpMethod.GET, "/api/v1/admin/market-status", email, null)
                .path("latestPersistedQuoteAt").isMissingNode()).isFalse();
        verifyNoInteractions(provider);
    }

    private JsonNode json(HttpMethod method, String path, String email, Object body) throws Exception {
        ResponseEntity<String> response = request(method, path, email, body);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("%s %s should succeed; response=%s", method, path, response.getBody()).isTrue();
        return response.hasBody() && response.getBody() != null && !response.getBody().isBlank()
                ? mapper.readTree(response.getBody()) : mapper.createObjectNode();
    }

    private void assertStatus(HttpMethod method, String path, String email, Object body, int expected) {
        assertThat(request(method, path, email, body).getStatusCode().value()).isEqualTo(expected);
    }

    private ResponseEntity<String> request(HttpMethod method, String path, String email, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        if (email != null) headers.set(HttpHeaders.AUTHORIZATION, basic(email));
        return http.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private static java.util.Map<String, Object> orderRequest() {
        return java.util.Map.of("exchange", "NSE", "symbol", "TCS", "side", "BUY",
                "orderType", "LIMIT", "tradingMode", "DELIVERY", "quantity", 1, "limitPrice", 100);
    }
}
