package com.tradecore.foundation.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Best-effort request admission counter. It is never used for financial correctness. */
@Component
public class ApiRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(ApiRateLimiter.class);
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); end; return n",
            Long.class);
    private static final String PREFIX = "tradecore:rate-limit:v1:";
    private final StringRedisTemplate redis;
    private final ApiRateLimitProperties properties;

    public ApiRateLimiter(StringRedisTemplate redis, ApiRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Returns true if admitted. Redis errors fail open. */
    public boolean allow(String endpoint, String clientIdentity) {
        ApiRateLimitProperties.Rule rule = rule(endpoint);
        String key = PREFIX + endpoint + ":" + digest(clientIdentity);
        try {
            Long count = redis.execute(INCREMENT_WITH_TTL, List.of(key), Long.toString(rule.window().toMillis()));
            return count == null || count <= rule.limit();
        } catch (RuntimeException unavailable) {
            log.warn("Rate limiter unavailable for {}; allowing request", endpoint);
            return true;
        }
    }

    public long retryAfterSeconds(String endpoint) {
        return Math.max(1, rule(endpoint).window().toSeconds());
    }

    private ApiRateLimitProperties.Rule rule(String endpoint) {
        return switch (endpoint) {
            case "register" -> properties.getRegister();
            case "order" -> properties.getOrder();
            case "cancel" -> properties.getCancel();
            default -> throw new IllegalArgumentException("Unsupported rate-limited endpoint");
        };
    }

    private static String digest(String identity) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((identity == null ? "unknown" : identity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
