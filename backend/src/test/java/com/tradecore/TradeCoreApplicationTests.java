package com.tradecore;

import com.tradecore.account.TradingAccountRepository;
import com.tradecore.identity.UserRepository;
import com.tradecore.market.InstrumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        properties = {
                "spring.datasource.url=jdbc:h2:mem:tradecore-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6379",
                "tradecore.security.user=test-user",
                "tradecore.security.password=test-password"
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TradeCoreApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TradingAccountRepository tradingAccountRepository;

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contextLoads() {
    }

    @Test
    void allCoreTablesHaveValidatedEntityMappings() {
        assertThat(entityManagerFactory.getMetamodel().getEntities()).hasSize(17);
    }

    @Test
    void userEmailUniquenessIsEnforcedByDatabase() {
        var userId = java.util.UUID.randomUUID();
        var email = "unique-" + userId + "@example.test";
        var now = java.time.Instant.now();
        jdbcTemplate.update("INSERT INTO app_user (id, email, password_hash, display_name, role, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                userId, email, "test-hash", "Test User", "USER", "ACTIVE", now, now);

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO app_user (id, email, password_hash, display_name, role, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                java.util.UUID.randomUUID(), email, "test-hash", "Duplicate", "USER", "ACTIVE", now, now))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void userRepositoryFindsUserByEmail() {
        var id = UUID.randomUUID();
        var email = "repository-" + id + "@example.test";
        insertUser(id, email);

        assertThat(userRepository.findByEmail(email)).isPresent();
        assertThat(userRepository.findByEmail("missing-" + email)).isEmpty();
    }

    @Test
    void tradingAccountRepositoryFindsAccountByUserId() {
        var userId = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        insertUser(userId, "account-" + userId + "@example.test");
        var now = Instant.now();
        jdbcTemplate.update("INSERT INTO trading_account (id, user_id, status, currency, available_balance, reserved_balance, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                accountId, userId, "ACTIVE", "INR", BigDecimal.ZERO, BigDecimal.ZERO, now, now);

        assertThat(tradingAccountRepository.findByUser_Id(userId)).isPresent();
        assertThat(tradingAccountRepository.findByUser_Id(UUID.randomUUID())).isEmpty();
    }

    @Test
    void databaseAllowsOnlyOneTradingAccountPerUser() {
        var userId = UUID.randomUUID();
        insertUser(userId, "single-account-" + userId + "@example.test");
        insertAccount(UUID.randomUUID(), userId);

        assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void executionMustMatchOrderAccountInstrumentSideAndMode() {
        var userId = UUID.randomUUID();
        insertUser(userId, "execution-invariant-" + userId + "@example.test");
        var accountId = UUID.randomUUID();
        insertAccount(accountId, userId);
        var otherUserId = UUID.randomUUID();
        insertUser(otherUserId, "other-execution-" + otherUserId + "@example.test");
        var otherAccountId = UUID.randomUUID();
        insertAccount(otherAccountId, otherUserId);
        var instrumentId = UUID.randomUUID();
        insertInstrument(instrumentId, "TEST", "ORD" + instrumentId.toString().replace("-", "").substring(0, 8), null, true);
        var otherInstrumentId = UUID.randomUUID();
        insertInstrument(otherInstrumentId, "TEST", "ALT" + otherInstrumentId.toString().replace("-", "").substring(0, 8), null, true);
        var orderId = UUID.randomUUID();
        insertOrder(orderId, accountId, instrumentId, "BUY", "DELIVERY");

        assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), otherAccountId, orderId, instrumentId, "BUY", "DELIVERY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), accountId, orderId, otherInstrumentId, "BUY", "DELIVERY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), accountId, orderId, instrumentId, "SELL", "DELIVERY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), accountId, orderId, instrumentId, "BUY", "INTRADAY"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertExecution(UUID.randomUUID(), accountId, orderId, instrumentId, "BUY", "DELIVERY");
    }

    @Test
    void idempotencyOrderAndLedgerReferencesMustMatchTheirAccount() {
        var userId = UUID.randomUUID();
        insertUser(userId, "reference-invariant-" + userId + "@example.test");
        var accountId = UUID.randomUUID();
        insertAccount(accountId, userId);
        var otherUserId = UUID.randomUUID();
        insertUser(otherUserId, "other-reference-" + otherUserId + "@example.test");
        var otherAccountId = UUID.randomUUID();
        insertAccount(otherAccountId, otherUserId);
        var instrumentId = UUID.randomUUID();
        insertInstrument(instrumentId, "TEST", "LED" + instrumentId.toString().replace("-", "").substring(0, 8), null, true);
        var orderId = UUID.randomUUID();
        insertOrder(orderId, accountId, instrumentId, "BUY", "DELIVERY");
        var executionId = UUID.randomUUID();
        insertExecution(executionId, accountId, orderId, instrumentId, "BUY", "DELIVERY");

        assertThatThrownBy(() -> insertIdempotencyRecord(otherAccountId, orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedgerEntry(otherAccountId, "TRADE_DEBIT", new BigDecimal("-10.00"), null, executionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedgerEntry(accountId, "TRADE_DEBIT", new BigDecimal("10.00"), null, executionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedgerEntry(accountId, "TRADE_DEBIT", new BigDecimal("-10.00"), orderId, executionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertIdempotencyRecord(accountId, orderId);
        insertLedgerEntry(accountId, "INITIAL_DEPOSIT", new BigDecimal("100000.00"), null, null);
        insertLedgerEntry(accountId, "TRADE_DEBIT", new BigDecimal("-10.00"), null, executionId);
    }

    @Test
    void instrumentRepositoryFindsByExchangeAndSymbol() {
        var instrumentId = UUID.randomUUID();
        var exchange = "TEST";
        var symbol = "SYM" + instrumentId.toString().replace("-", "").substring(0, 8);
        insertInstrument(instrumentId, exchange, symbol, null, true);

        assertThat(instrumentRepository.findByExchangeAndSymbol(exchange, symbol)).isPresent();
        assertThat(instrumentRepository.findByExchangeAndSymbol(exchange, "missing-" + symbol)).isEmpty();
    }

    @Test
    void instrumentRepositoryFindsProviderMappingAndTradableInstruments() {
        var instrumentId = UUID.randomUUID();
        var providerKey = "test-provider-key-" + instrumentId;
        insertInstrument(instrumentId, "TEST", "MAP" + instrumentId.toString().replace("-", "").substring(0, 8), providerKey, true);

        assertThat(instrumentRepository.findByProviderInstrumentKey(providerKey)).isPresent();
        assertThat(instrumentRepository.findAllByTradableTrue()).isNotEmpty();
    }

    private void insertUser(UUID id, String email) {
        var now = Instant.now();
        jdbcTemplate.update("INSERT INTO app_user (id, email, password_hash, display_name, role, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, email, "test-hash", "Repository Test", "USER", "ACTIVE", now, now);
    }

    private void insertAccount(UUID accountId, UUID userId) {
        var now = Instant.now();
        jdbcTemplate.update("INSERT INTO trading_account (id, user_id, status, currency, available_balance, reserved_balance, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                accountId, userId, "ACTIVE", "INR", BigDecimal.ZERO, BigDecimal.ZERO, now, now);
    }

    private void insertOrder(UUID orderId, UUID accountId, UUID instrumentId, String side, String mode) {
        var now = Instant.now();
        jdbcTemplate.update("INSERT INTO trading_order (id, account_id, instrument_id, side, order_type, trading_mode, requested_quantity, remaining_quantity, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId, accountId, instrumentId, side, "MARKET", mode, 1L, 1L, "PENDING", now, now);
    }

    private void insertExecution(UUID executionId, UUID accountId, UUID orderId, UUID instrumentId, String side, String mode) {
        jdbcTemplate.update("INSERT INTO execution (id, account_id, order_id, instrument_id, side, trading_mode, quantity, price, executed_at, fee) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                executionId, accountId, orderId, instrumentId, side, mode, 1L, new BigDecimal("10.000000"), Instant.now(), BigDecimal.ZERO);
    }

    private void insertIdempotencyRecord(UUID accountId, UUID orderId) {
        jdbcTemplate.update("INSERT INTO idempotency_record (id, account_id, idempotency_key, request_fingerprint, state, original_order_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), accountId, "key-" + UUID.randomUUID(), "fingerprint", "COMPLETED", orderId, Instant.now());
    }

    private void insertLedgerEntry(UUID accountId, String entryType, BigDecimal amount, UUID orderId, UUID executionId) {
        jdbcTemplate.update("INSERT INTO ledger_entry (id, account_id, entry_type, amount, currency, order_id, execution_id, description, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), accountId, entryType, amount, "INR", orderId, executionId, "Invariant test", Instant.now());
    }

    private void insertInstrument(UUID id, String exchange, String symbol, String providerKey, boolean tradable) {
        var now = Instant.now();
        jdbcTemplate.update("INSERT INTO instrument (id, symbol, company_name, exchange, instrument_type, currency, tradable, provider_instrument_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, symbol, "Repository Fixture", exchange, "EQUITY", "INR", tradable, providerKey, now, now);
    }

    @Test
    void healthIsPublicAndFoundationApiRequiresConfiguredCredentials() {
        var liveness = restTemplate.getForEntity("/actuator/health/liveness", String.class);
        var anonymousStatus = restTemplate.getForEntity("/api/v1/foundation/status", String.class);
        var authenticatedStatus = restTemplate.withBasicAuth("test-user", "test-password")
                .getForEntity("/api/v1/foundation/status", String.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anonymousStatus.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(authenticatedStatus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authenticatedStatus.getBody()).contains("TradeCore backend is running");
    }

    @Test
    @EnabledIfSystemProperty(named = "tradecore.nse-mcp.live-test", matches = "true")
    void nseMcpLiveConnectivitySmokeTest() throws Exception {
        var providerHealth = restTemplate.withBasicAuth("test-user", "test-password")
                .getForEntity("/actuator/health", String.class);

        JsonNode rootHealth = objectMapper.readTree(providerHealth.getBody());
        JsonNode endpoints = findProviderEndpoints(rootHealth.path("components"));
        assertThat(endpoints.path("market-live").path("connected").asBoolean()).isTrue();
        assertThat(endpoints.path("bhavcopy").path("connected").asBoolean()).isTrue();
    }

    private JsonNode findProviderEndpoints(JsonNode healthComponents) {
        var components = healthComponents.elements();
        while (components.hasNext()) {
            JsonNode component = components.next();
            JsonNode endpoints = component.path("details").path("endpoints");
            if (endpoints.has("market-live") || endpoints.has("bhavcopy")) {
                return endpoints;
            }
        }
        return objectMapper.createObjectNode();
    }
}
