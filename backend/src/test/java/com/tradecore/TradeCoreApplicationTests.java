package com.tradecore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

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
}
