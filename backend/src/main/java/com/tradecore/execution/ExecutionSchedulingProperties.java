package com.tradecore.execution;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tradecore.execution.scheduling")
public class ExecutionSchedulingProperties {
    private boolean enabled = true;
    private Duration interval = Duration.ofSeconds(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getInterval() { return interval; }
    public void setInterval(Duration interval) { this.interval = interval; }
}
