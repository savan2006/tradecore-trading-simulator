package com.tradecore.market;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("marketDataProvider")
public class MarketDataProviderHealthIndicator implements HealthIndicator {

    private final MarketDataProvider provider;

    public MarketDataProviderHealthIndicator(MarketDataProvider provider) {
        this.provider = provider;
    }

    @Override
    public Health health() {
        MarketDataConnectivity connectivity = provider.checkConnectivity();
        Health.Builder health = connectivity.connected() ? Health.up() : Health.down();
        return health
                .withDetail("checkedAt", connectivity.checkedAt())
                .withDetail("endpoints", connectivity.endpoints().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> Map.of(
                                "connected", entry.getValue().connected(),
                                "protocolVersion", entry.getValue().protocolVersion() == null
                                        ? "unavailable" : entry.getValue().protocolVersion(),
                                "availableOperations", entry.getValue().availableOperations(),
                                "failureCategory", entry.getValue().failureCategory() == null
                                        ? "none" : entry.getValue().failureCategory()))))
                .build();
    }
}
