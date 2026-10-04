package com.tradecore.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.RegistrationResponse;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketQuoteRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-portfolio-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=portfolio-test",
        "tradecore.security.password=portfolio-test-password", "tradecore.execution.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class PortfolioApiTest {
    private static final String PASSWORD = "Portfolio-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @MockitoSpyBean private MarketQuoteRepository quoteRepository;
    private UUID tcsId;
    private UUID infyId;

    @BeforeEach
    void seedFreshQuotes() {
        tcsId = instrumentId("TCS");
        infyId = instrumentId("INFY");
        putQuote(tcsId, "110", Instant.now(), "LIVE");
        putQuote(infyId, "200", Instant.now(), "LIVE");
        clearInvocations(quoteRepository);
    }

    @Test
    void emptyPortfolioReturnsZeroValuation() throws Exception {
        Account user = account();
        var response = portfolio(user);
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(100000.0))
                .andExpect(jsonPath("$.reservedBalance").value(0.0))
                .andExpect(jsonPath("$.investedCost").value(0.0))
                .andExpect(jsonPath("$.currentMarketValue").value(0.0))
                .andExpect(jsonPath("$.realizedPnl").value(0.0))
                .andExpect(jsonPath("$.unrealizedPnl").value(0.0))
                .andExpect(jsonPath("$.totalPnl").value(0.0))
                .andExpect(jsonPath("$.positionCount").value(0))
                .andExpect(jsonPath("$.valuationStatus").value("EMPTY"));
    }

    @Test
    void oneBuyPositionUsesAverageCostAndCurrentMarketValue() throws Exception {
        Account user = account(); seedPosition(user.id, tcsId, "DELIVERY", 4, 1, "100.000000", "0.0000");

        portfolio(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.investedCost").value(400.0))
                .andExpect(jsonPath("$.currentMarketValue").value(440.0))
                .andExpect(jsonPath("$.unrealizedPnl").value(40.0))
                .andExpect(jsonPath("$.totalPnl").value(40.0))
                .andExpect(jsonPath("$.positions[0].symbol").value("TCS"))
                .andExpect(jsonPath("$.positions[0].averageCost").value(100.0))
                .andExpect(jsonPath("$.positions[0].sellableQuantity").value(3));
    }

    @Test
    void multiplePositionsAggregateValueAndPreservePerInstrumentAverageCost() throws Exception {
        Account user = account();
        seedPosition(user.id, tcsId, "DELIVERY", 2, 0, "105.000000", "0.0000");
        seedPosition(user.id, infyId, "DELIVERY", 3, 0, "180.000000", "0.0000");

        portfolio(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.positionCount").value(2))
                .andExpect(jsonPath("$.investedCost").value(750.0))
                .andExpect(jsonPath("$.currentMarketValue").value(820.0))
                .andExpect(jsonPath("$.unrealizedPnl").value(70.0));
        verify(quoteRepository, times(1)).findAllByInstrument_IdIn(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void realizedPnlAlreadyStoredOnPositionContributesToTotalPnl() throws Exception {
        Account user = account(); seedPosition(user.id, tcsId, "DELIVERY", 4, 0, "100", "25.5000");

        portfolio(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.realizedPnl").value(25.5))
                .andExpect(jsonPath("$.unrealizedPnl").value(40.0))
                .andExpect(jsonPath("$.totalPnl").value(65.5));
    }

    @Test
    void realizedPnlRemainsInSummaryAfterPositionIsFullyClosed() throws Exception {
        Account user = account(); seedPosition(user.id, tcsId, "DELIVERY", 0, 0, "0", "18.2500");

        portfolio(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.positionCount").value(0))
                .andExpect(jsonPath("$.positions.length()").value(0))
                .andExpect(jsonPath("$.realizedPnl").value(18.25))
                .andExpect(jsonPath("$.totalPnl").value(18.25));
    }

    @Test
    void staleQuoteMakesPositionPriceAndAggregateValuationUnavailable() throws Exception {
        Account user = account(); seedPosition(user.id, tcsId, "DELIVERY", 4, 0, "100", "5");
        putQuote(tcsId, "110", Instant.now().minusSeconds(601), "LIVE");

        portfolio(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].valuationStatus").value("STALE"))
                .andExpect(jsonPath("$.positions[0].currentPrice").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.positions[0].marketValue").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.positions[0].unrealizedPnl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.currentMarketValue").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.unrealizedPnl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.realizedPnl").value(5.0))
                .andExpect(jsonPath("$.totalPnl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.valuationStatus").value("STALE"));
    }

    @Test
    void missingQuoteIsExplicitlyUnavailableWithoutInventingAValue() throws Exception {
        Account user = account(); seedPosition(user.id, tcsId, "DELIVERY", 4, 0, "100", "0");
        jdbc.update("delete from market_quote where instrument_id=?", tcsId);

        portfolio(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].valuationStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.positions[0].currentPrice").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.currentMarketValue").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.valuationStatus").value("UNAVAILABLE"));
    }

    @Test
    void deliveryAndIntradayHoldingsRemainSeparatePositions() throws Exception {
        Account user = account();
        seedPosition(user.id, tcsId, "DELIVERY", 2, 1, "100", "0");
        seedPosition(user.id, tcsId, "INTRADAY", 3, 0, "105", "2");

        JsonNode body = mapper.readTree(portfolio(user).andReturn().getResponse().getContentAsString());
        assertThat(body.path("positionCount").asInt()).isEqualTo(2);
        assertThat(body.path("positions").get(0).path("tradingMode").asText()).isEqualTo("DELIVERY");
        assertThat(body.path("positions").get(1).path("tradingMode").asText()).isEqualTo("INTRADAY");
        assertThat(body.path("currentMarketValue").decimalValue()).isEqualByComparingTo("550.0000");
        assertThat(body.path("realizedPnl").decimalValue()).isEqualByComparingTo("2.0000");
    }

    @Test
    void authenticatedUserOnlySeesTheirOwnAccountPositions() throws Exception {
        Account owner = account(), other = account();
        seedPosition(owner.id, tcsId, "DELIVERY", 4, 0, "100", "0");
        seedPosition(other.id, infyId, "DELIVERY", 3, 0, "180", "0");

        portfolio(owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.positionCount").value(1))
                .andExpect(jsonPath("$.positions[0].symbol").value("TCS"));
        portfolio(other).andExpect(status().isOk())
                .andExpect(jsonPath("$.positionCount").value(1))
                .andExpect(jsonPath("$.positions[0].symbol").value("INFY"));
    }

    @Test
    void unauthenticatedUserCannotReadPortfolio() throws Exception {
        mvc.perform(get("/api/v1/portfolio/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void portfolioReadDoesNotMutateAccountOrPositionState() throws Exception {
        Account user = account(); seedPosition(user.id, tcsId, "DELIVERY", 4, 2, "100", "10");
        var before = jdbc.queryForMap("select available_balance,reserved_balance,updated_at,version from trading_account where id=?", user.id);
        var positionBefore = jdbc.queryForMap("select quantity,reserved_quantity,average_price,realized_pnl,updated_at,version from position where account_id=?", user.id);
        int ledgerBefore = jdbc.queryForObject("select count(*) from ledger_entry where account_id=?", Integer.class, user.id);

        portfolio(user).andExpect(status().isOk());

        assertThat(jdbc.queryForMap("select available_balance,reserved_balance,updated_at,version from trading_account where id=?", user.id)).isEqualTo(before);
        assertThat(jdbc.queryForMap("select quantity,reserved_quantity,average_price,realized_pnl,updated_at,version from position where account_id=?", user.id)).isEqualTo(positionBefore);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where account_id=?", Integer.class, user.id)).isEqualTo(ledgerBefore);
    }

    private org.springframework.test.web.servlet.ResultActions portfolio(Account user) throws Exception {
        return mvc.perform(get("/api/v1/portfolio/me").header("Authorization", basic(user.email)));
    }
    private Account account() {
        String email = "portfolio-" + UUID.randomUUID() + "@example.invalid";
        RegistrationResponse result = registration.register(new RegistrationRequest(email, PASSWORD, "Portfolio Test"));
        return new Account(email, result.accountId());
    }
    private UUID instrumentId(String symbol) {
        return jdbc.queryForObject("select id from instrument where exchange='NSE' and symbol=?", UUID.class, symbol);
    }
    private void seedPosition(UUID account, UUID instrument, String mode, long quantity, long reserved,
            String average, String realized) {
        jdbc.update("insert into position (id,account_id,instrument_id,trading_mode,quantity,reserved_quantity,average_price,realized_pnl,updated_at,version) values (?,?,?,?,?,?,?,?,?,0)",
                UUID.randomUUID(), account, instrument, mode, quantity, reserved, new BigDecimal(average),
                new BigDecimal(realized), Timestamp.from(Instant.now()));
    }
    private void putQuote(UUID instrument, String price, Instant providerTime, String dataStatus) {
        jdbc.update("delete from market_quote where instrument_id=?", instrument);
        jdbc.update("insert into market_quote (id,instrument_id,last_price,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,'OPEN',?)",
                UUID.randomUUID(), instrument, new BigDecimal(price), Timestamp.from(providerTime),
                Timestamp.from(providerTime), Timestamp.from(Instant.now()), dataStatus);
    }
    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
    private record Account(String email, UUID id) { }
}
