package com.tradecore.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataProviderHealthIndicatorTest {

    @Test
    void healthIndicatorUsesTheProviderIndependentConnectivityContract() {
        var provider = connectivityProvider(new MarketDataConnectivity(
                true,
                Instant.parse("2026-10-04T00:00:00Z"),
                Map.of("current", new MarketDataConnectivity.EndpointStatus(true, "2025-11-25", 3, null))));

        var health = new MarketDataProviderHealthIndicator(provider).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsKey("endpoints");
    }

    @Test
    void healthIndicatorReportsProviderFailureWithoutExposingProviderResponseObjects() {
        var provider = connectivityProvider(new MarketDataConnectivity(
                false,
                Instant.parse("2026-10-04T00:00:00Z"),
                Map.of("current", new MarketDataConnectivity.EndpointStatus(false, null, 0, "TIMEOUT"))));

        var health = new MarketDataProviderHealthIndicator(provider).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).doesNotContainKey("mcpResponse");
    }

    private static MarketDataProvider connectivityProvider(MarketDataConnectivity connectivity) {
        return new MarketDataProvider() {
            @Override public MarketDataConnectivity checkConnectivity() { return connectivity; }
            @Override public List<MarketInstrument> searchInstruments(String query) { throw new UnsupportedOperationException(); }
            @Override public MarketQuoteSnapshot getQuote(String symbol) { throw new UnsupportedOperationException(); }
            @Override public List<MarketCandleSnapshot> getHistoricalCandles(String symbol, int months, LocalDate endDate) { throw new UnsupportedOperationException(); }
            @Override public Optional<MarketDataFreshness> getDataFreshness() { throw new UnsupportedOperationException(); }
            @Override public Optional<MarketSessionStatus> getMarketSessionStatus() { return Optional.empty(); }
        };
    }
}
