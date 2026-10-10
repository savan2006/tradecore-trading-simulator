package com.tradecore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        properties = {
                "SPRING_DATASOURCE_URL=jdbc:h2:mem:tradecore-production-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "SPRING_DATASOURCE_USERNAME=sa",
                "SPRING_DATASOURCE_PASSWORD=test-password",
                "SPRING_DATA_REDIS_HOST=localhost",
                "SPRING_DATA_REDIS_PORT=6379",
                "SPRING_DATA_REDIS_PASSWORD=test-password",
                "SPRING_DATA_REDIS_SSL_ENABLED=false",
                "TRADECORE_CORS_ALLOWED_ORIGINS=https://tradecore.example.test",
                "TRADECORE_WEBSOCKET_ALLOWED_ORIGINS=https://tradecore.example.test",
                "TRADECORE_SECURITY_TRUSTED_PROXY_CIDRS=127.0.0.1/32",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@ActiveProfiles("production")
class ProductionProfileConfigurationTest {
    @Autowired
    private Environment environment;

    @Test
    void loadsProductionProfileWithRequiredPlaceholderValues() {
        assertThat(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production"))).isTrue();
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
        assertThat(environment.getProperty("spring.jpa.show-sql", Boolean.class)).isFalse();
        assertThat(environment.getProperty("tradecore.security.dev-user-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("tradecore.docs.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("tradecore.cors.allowed-origins"))
                .isEqualTo("https://tradecore.example.test");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .contains("metrics");
    }
}
