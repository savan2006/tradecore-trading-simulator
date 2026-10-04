package com.tradecore.market;

import java.time.Instant;
import java.util.Map;

/** Neutral health information returned by a market-data provider. */
public record MarketDataConnectivity(
        boolean connected,
        Instant checkedAt,
        Map<String, EndpointStatus> endpoints) {

    public MarketDataConnectivity {
        endpoints = Map.copyOf(endpoints);
    }

    public record EndpointStatus(
            boolean connected,
            String protocolVersion,
            int availableOperations,
            String failureCategory) {
    }
}
