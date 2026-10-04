package com.tradecore.market;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataProviderHealthIndicatorTest {

    @Test
    void healthIndicatorUsesTheProviderIndependentConnectivityContract() {
        var provider = (MarketDataProvider) () -> new MarketDataConnectivity(
                true,
                Instant.parse("2026-10-04T00:00:00Z"),
                Map.of("current", new MarketDataConnectivity.EndpointStatus(true, "2025-11-25", 3, null)));

        var health = new MarketDataProviderHealthIndicator(provider).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsKey("endpoints");
    }

    @Test
    void healthIndicatorReportsProviderFailureWithoutExposingProviderResponseObjects() {
        var provider = (MarketDataProvider) () -> new MarketDataConnectivity(
                false,
                Instant.parse("2026-10-04T00:00:00Z"),
                Map.of("current", new MarketDataConnectivity.EndpointStatus(false, null, 0, "TIMEOUT")));

        var health = new MarketDataProviderHealthIndicator(provider).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).doesNotContainKey("mcpResponse");
    }
}
