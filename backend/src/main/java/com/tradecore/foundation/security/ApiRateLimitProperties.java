package com.tradecore.foundation.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tradecore.rate-limit")
public class ApiRateLimitProperties {
    private Rule register = new Rule(5, Duration.ofHours(1));
    private Rule order = new Rule(20, Duration.ofMinutes(1));
    private Rule cancel = new Rule(30, Duration.ofMinutes(1));
    private Rule authenticationFailure = new Rule(10, Duration.ofMinutes(5));

    public Rule getRegister() { return register; }
    public void setRegister(Rule register) { this.register = valid(register, "register"); }
    public Rule getOrder() { return order; }
    public void setOrder(Rule order) { this.order = valid(order, "order"); }
    public Rule getCancel() { return cancel; }
    public void setCancel(Rule cancel) { this.cancel = valid(cancel, "cancel"); }
    public Rule getAuthenticationFailure() { return authenticationFailure; }
    public void setAuthenticationFailure(Rule authenticationFailure) {
        this.authenticationFailure = valid(authenticationFailure, "authentication-failure");
    }

    private static Rule valid(Rule value, String name) {
        if (value == null || value.limit() < 1 || value.window() == null
                || value.window().isZero() || value.window().isNegative()) {
            throw new IllegalArgumentException("Invalid rate limit configuration for " + name);
        }
        return value;
    }

    public record Rule(int limit, Duration window) { }
}
