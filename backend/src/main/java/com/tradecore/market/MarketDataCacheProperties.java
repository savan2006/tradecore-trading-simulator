package com.tradecore.market;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tradecore.market-data.cache")
public class MarketDataCacheProperties {
    private Duration quoteTtl = Duration.ofSeconds(15);
    private Duration instrumentTtl = Duration.ofHours(1);

    public Duration getQuoteTtl() { return quoteTtl; }
    public void setQuoteTtl(Duration quoteTtl) { this.quoteTtl = positive(quoteTtl, "quoteTtl"); }
    public Duration getInstrumentTtl() { return instrumentTtl; }
    public void setInstrumentTtl(Duration instrumentTtl) { this.instrumentTtl = positive(instrumentTtl, "instrumentTtl"); }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
        return value;
    }
}
