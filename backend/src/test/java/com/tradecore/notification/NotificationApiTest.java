package com.tradecore.notification;

import com.tradecore.identity.RegistrationRequest;
import com.tradecore.identity.UserRegistrationService;
import com.tradecore.market.MarketDataProvider;
import java.nio.charset.StandardCharsets;
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

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-notifications-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=notifications-test",
        "tradecore.security.password=notifications-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false",
        "tradecore.alerts.processing-interval=PT1H"
})
@AutoConfigureMockMvc
class NotificationApiTest {
    private static final String PASSWORD = "Notification-Test-Password-93!";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @MockitoSpyBean private MarketDataProvider provider;
    private String owner;
    private String other;
    private UUID first;
    private UUID second;
    private UUID third;

    @BeforeEach
    void setUp() {
        owner = account();
        other = account();
        Instant now = Instant.now();
        first = notification(owner, "older", now.minusSeconds(3));
        second = notification(owner, "middle", now.minusSeconds(2));
        third = notification(owner, "newer", now.minusSeconds(1));
        notification(other, "private", now);
        clearInvocations(provider);
    }

    @Test
    void listsOnlyOwnNotificationsNewestFirstWithBoundedPaginationAndUnreadFilter() throws Exception {
        mvc.perform(get("/api/v1/notifications").param("page", "0").param("size", "2")
                        .header("Authorization", basic(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(third.toString()))
                .andExpect(jsonPath("$.items[1].id").value(second.toString()))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").param("size", "101")
                        .header("Authorization", basic(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications").header("Authorization", basic(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].message").value("private"));
        verifyNoInteractions(provider);
    }

    @Test
    void unreadCountMarkOneAndMarkAllAreOwnedAndIdempotent() throws Exception {
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", basic(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(3));
        mvc.perform(post("/api/v1/notifications/{id}/read", second).header("Authorization", basic(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(second.toString()))
                .andExpect(jsonPath("$.readAt").isNotEmpty());
        String firstReadAt = jdbc.queryForObject("select read_at from notification where id=?", String.class, second);
        mvc.perform(post("/api/v1/notifications/{id}/read", second).header("Authorization", basic(owner)))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select read_at from notification where id=?", String.class, second)).isEqualTo(firstReadAt);
        mvc.perform(post("/api/v1/notifications/{id}/read", second).header("Authorization", basic(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").header("Authorization", basic(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", basic(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedCount").value(2));
        mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", basic(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedCount").value(0));
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", basic(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", basic(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
        verifyNoInteractions(provider);
    }

    @Test
    void notificationEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/{id}/read", first)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/read-all")).andExpect(status().isUnauthorized());
    }

    private String account() {
        return registration.register(new RegistrationRequest("notification-" + UUID.randomUUID() + "@example.invalid",
                PASSWORD, "Notification Test")).email();
    }

    private UUID notification(String email, String message, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notification (id,user_id,notification_type,title,message,created_at) " +
                        "select ?,id,'PRICE_ALERT','Test',?,? from app_user where email=?",
                id, message, java.sql.Timestamp.from(createdAt), email);
        return id;
    }

    private static String basic(String email) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
