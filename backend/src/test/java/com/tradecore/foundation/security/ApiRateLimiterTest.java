package com.tradecore.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ApiRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ApiRateLimitProperties properties = new ApiRateLimitProperties();
    private final Map<String, Long> counts = new HashMap<>();
    private ApiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties.setRegister(new ApiRateLimitProperties.Rule(2, Duration.ofMinutes(2)));
        limiter = new ApiRateLimiter(redis, properties);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> keys = invocation.getArgument(1);
            return counts.merge(keys.get(0), 1L, Long::sum);
        });
    }

    @Test
    void allowsRequestsUnderLimitAndRejectsOverLimit() {
        assertThat(limiter.allow("register", "ip:192.0.2.1")).isTrue();
        assertThat(limiter.allow("register", "ip:192.0.2.1")).isTrue();
        assertThat(limiter.allow("register", "ip:192.0.2.1")).isFalse();
    }

    @Test
    void clientsHaveSeparateCountersAndConfiguredWindow() {
        assertThat(limiter.allow("register", "ip:192.0.2.1")).isTrue();
        assertThat(limiter.allow("register", "ip:192.0.2.1")).isTrue();
        assertThat(limiter.allow("register", "ip:192.0.2.2")).isTrue();
        assertThat(limiter.retryAfterSeconds("register")).isEqualTo(120);
    }

    @Test
    void redisFailureFailsOpen() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        assertThat(limiter.allow("order", "user:person@example.invalid")).isTrue();
    }

    @Test
    void defaultsAndLimitsAreConfigurable() {
        ApiRateLimitProperties defaults = new ApiRateLimitProperties();
        assertThat(defaults.getRegister()).isEqualTo(new ApiRateLimitProperties.Rule(5, Duration.ofHours(1)));
        assertThat(defaults.getOrder()).isEqualTo(new ApiRateLimitProperties.Rule(20, Duration.ofMinutes(1)));
        assertThat(defaults.getCancel()).isEqualTo(new ApiRateLimitProperties.Rule(30, Duration.ofMinutes(1)));
        defaults.setOrder(new ApiRateLimitProperties.Rule(7, Duration.ofSeconds(30)));
        assertThat(defaults.getOrder()).isEqualTo(new ApiRateLimitProperties.Rule(7, Duration.ofSeconds(30)));
    }
}
